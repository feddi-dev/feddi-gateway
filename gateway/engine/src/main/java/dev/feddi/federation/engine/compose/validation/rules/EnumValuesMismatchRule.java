package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import graphql.introspection.Introspection;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLEnumValueDefinition;
import graphql.schema.GraphQLNamedType;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static dev.feddi.federation.engine.compose.FederationDirectives.INACCESSIBLE;

/**
 * Validates that enum types with the same name have the same values in every source schema, apart from
 * values that are {@code @inaccessible} in any of them.
 *
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Enum-Values-Mismatch
 */
public final class EnumValuesMismatchRule implements ValidationRule {

    private static final String CODE = "ENUM_VALUES_MISMATCH";

    @Override
    public ValidationPhase phase() {
        return ValidationPhase.PRE_MERGE;
    }

    @Override
    public String name() {
        return "EnumValuesMismatchRule";
    }

    @Override
    public ValidationResult validate(List<Subgraph> subgraphs) {
        ValidationResult.Builder builder = ValidationResult.builder();

        Map<String, Map<String, GraphQLEnumType>> enumsByName = new LinkedHashMap<>();
        for (Subgraph subgraph : subgraphs) {
            for (GraphQLNamedType type : subgraph.schema().getAllTypesAsList()) {
                if (type instanceof GraphQLEnumType enumType && !Introspection.isIntrospectionTypes(type.getName())) {
                    enumsByName.computeIfAbsent(type.getName(), k -> new LinkedHashMap<>())
                        .put(subgraph.name(), enumType);
                }
            }
        }

        for (Map.Entry<String, Map<String, GraphQLEnumType>> entry : enumsByName.entrySet()) {
            Map<String, GraphQLEnumType> enums = entry.getValue();
            if (enums.size() < 2) {
                continue;
            }
            Set<String> inaccessibleValues = new HashSet<>();
            Set<String> requiredValues = new LinkedHashSet<>();
            for (GraphQLEnumType enumType : enums.values()) {
                for (GraphQLEnumValueDefinition value : enumType.getValues()) {
                    requiredValues.add(value.getName());
                    if (value.hasAppliedDirective(INACCESSIBLE)) {
                        inaccessibleValues.add(value.getName());
                    }
                }
            }
            requiredValues.removeAll(inaccessibleValues);

            for (Map.Entry<String, GraphQLEnumType> schemaEnum : enums.entrySet()) {
                Set<String> missing = new TreeSet<>(requiredValues);
                schemaEnum.getValue().getValues().forEach(value -> missing.remove(value.getName()));
                if (!missing.isEmpty()) {
                    builder.addError(CODE,
                        String.format("Enum '%s' in schema '%s' lacks the values %s that other schemas define; "
                                + "mark them @inaccessible to keep them out of the composite schema.",
                            entry.getKey(), schemaEnum.getKey(), missing),
                        entry.getKey(), schemaEnum.getKey());
                }
            }
        }

        return builder.build();
    }
}
