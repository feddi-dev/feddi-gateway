package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.feddi.federation.engine.compose.FederationDirectives.EXTERNAL;

/**
 * Validates that fields marked @external have a non-external definition in at least one other schema.
 *
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-External-Missing-on-Base
 */
public final class ExternalMissingOnBaseRule implements ValidationRule {

    private static final String CODE = "EXTERNAL_MISSING_ON_BASE";

    private static final Set<String> BUILT_IN_TYPES = Set.of(
        "String", "Int", "Float", "Boolean", "ID",
        "__Schema", "__Type", "__Field", "__InputValue", "__EnumValue",
        "__TypeKind", "__Directive", "__DirectiveLocation"
    );

    @Override
    public ValidationPhase phase() {
        return ValidationPhase.PRE_MERGE;
    }

    @Override
    public String name() {
        return "ExternalMissingOnBaseRule";
    }

    @Override
    public ValidationResult validate(List<Subgraph> subgraphs) {
        ValidationResult.Builder builder = ValidationResult.builder();

        // Collect all fields by coordinate across all subgraphs
        Map<String, List<FieldInfo>> fieldsByCoordinate = new LinkedHashMap<>();

        for (Subgraph subgraph : subgraphs) {
            for (GraphQLType type : subgraph.schema().getAllTypesAsList()) {
                if (type instanceof GraphQLObjectType objType) {
                    collectFields(objType, subgraph.name(), fieldsByCoordinate);
                } else if (type instanceof GraphQLInterfaceType ifaceType) {
                    collectFields(ifaceType, subgraph.name(), fieldsByCoordinate);
                }
            }
        }

        // Check for external fields missing base definition
        for (Map.Entry<String, List<FieldInfo>> entry : fieldsByCoordinate.entrySet()) {
            String coordinate = entry.getKey();
            List<FieldInfo> fieldInfos = entry.getValue();

            boolean hasExternal = fieldInfos.stream().anyMatch(f -> f.isExternal);
            boolean hasNonExternal = fieldInfos.stream().anyMatch(f -> !f.isExternal);

            if (hasExternal && !hasNonExternal) {
                // Find which schemas have the external field for error message
                List<String> externalSchemas = fieldInfos.stream()
                    .filter(f -> f.isExternal)
                    .map(f -> f.schemaName)
                    .toList();

                String message = String.format(
                    "Field '%s' is marked @external in %s but has no non-external definition in any schema.",
                    coordinate, externalSchemas
                );
                builder.addError(CODE, message, coordinate, null);
            }
        }

        return builder.build();
    }

    private void collectFields(GraphQLFieldsContainer type, String schemaName,
                               Map<String, List<FieldInfo>> fieldsByCoordinate) {
        String typeName = ((GraphQLNamedType) type).getName();
        if (BUILT_IN_TYPES.contains(typeName)) {
            return;
        }

        for (GraphQLFieldDefinition field : type.getFieldDefinitions()) {
            String coordinate = typeName + "." + field.getName();
            boolean isExternal = field.hasAppliedDirective(EXTERNAL);
            fieldsByCoordinate
                .computeIfAbsent(coordinate, k -> new ArrayList<>())
                .add(new FieldInfo(schemaName, isExternal));
        }
    }

    private record FieldInfo(String schemaName, boolean isExternal) {}
}
