package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLAppliedDirectiveArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.feddi.federation.engine.compose.FederationDirectives.OVERRIDE;

/**
 * Validates that @override directives do not form cycles or have multiple sources.
 *
 * A field marked with @override signifies that its ownership is being taken over
 * by another schema. If multiple schemas try to override the same field, or if the
 * ownership chain loops back on itself, the composed schema has ambiguity about
 * which schema ultimately owns that field.
 *
 * Only one @override may apply to a particular field across all source schemas.
 * Cycles or multiple overrides for the same field trigger this error.
 *
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Override-Source-Has-Override
 */
public final class OverrideSourceHasOverrideRule implements ValidationRule {

    private static final String CODE = "OVERRIDE_SOURCE_HAS_OVERRIDE";

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
        return "OverrideSourceHasOverrideRule";
    }

    @Override
    public ValidationResult validate(List<Subgraph> subgraphs) {
        ValidationResult.Builder builder = ValidationResult.builder();

        // Build a map of schema name -> Subgraph for lookups
        Map<String, Subgraph> subgraphByName = new HashMap<>();
        for (Subgraph subgraph : subgraphs) {
            subgraphByName.put(subgraph.name(), subgraph);
        }

        // Group fields by type name and field name across all subgraphs
        // Key: "TypeName.fieldName" -> List of (schemaName, field, overrideFrom)
        Map<String, List<FieldOverrideInfo>> fieldsByCoordinate = new LinkedHashMap<>();

        for (Subgraph subgraph : subgraphs) {
            collectOverrideFields(subgraph, fieldsByCoordinate);
        }

        // Check each field coordinate for override cycles or conflicts
        for (Map.Entry<String, List<FieldOverrideInfo>> entry : fieldsByCoordinate.entrySet()) {
            String coordinate = entry.getKey();
            List<FieldOverrideInfo> fieldInfos = entry.getValue();

            // Count fields with @override
            List<FieldOverrideInfo> overrides = fieldInfos.stream()
                .filter(f -> f.overrideFrom != null)
                .toList();

            if (overrides.size() < 2) {
                continue; // Need at least 2 overrides to have a conflict
            }

            // Build a map of schema name -> override target
            Map<String, String> overrideChain = new HashMap<>();
            for (FieldOverrideInfo info : overrides) {
                overrideChain.put(info.schemaName, info.overrideFrom);
            }

            // Check for cycles and verify chain validity
            for (FieldOverrideInfo startInfo : overrides) {
                Set<String> visited = new HashSet<>();
                String current = startInfo.schemaName;
                visited.add(current);

                String from = startInfo.overrideFrom;
                while (from != null) {
                    if (visited.contains(from)) {
                        // Cycle detected
                        String message = String.format(
                            "Field '%s' has an @override cycle: schema '%s' is part of a circular override chain.",
                            coordinate, from
                        );
                        builder.addError(CODE, message, coordinate, startInfo.schemaName);
                        break;
                    }
                    visited.add(from);

                    // Check if the source schema also has an @override
                    String nextFrom = overrideChain.get(from);
                    if (nextFrom == null) {
                        // End of chain - this is valid
                        break;
                    }
                    from = nextFrom;
                }
            }

            // Check for multiple schemas overriding the same source (fork)
            Map<String, List<String>> overridesBySource = new HashMap<>();
            for (FieldOverrideInfo info : overrides) {
                overridesBySource
                    .computeIfAbsent(info.overrideFrom, k -> new ArrayList<>())
                    .add(info.schemaName);
            }

            for (Map.Entry<String, List<String>> sourceEntry : overridesBySource.entrySet()) {
                String source = sourceEntry.getKey();
                List<String> overridingSchemas = sourceEntry.getValue();
                if (overridingSchemas.size() > 1) {
                    String message = String.format(
                        "Field '%s' is overridden by multiple schemas [%s] from the same source '%s'.",
                        coordinate, String.join(", ", overridingSchemas), source
                    );
                    builder.addError(CODE, message, coordinate, overridingSchemas.get(0));
                }
            }
        }

        return builder.build();
    }

    private void collectOverrideFields(Subgraph subgraph,
                                       Map<String, List<FieldOverrideInfo>> fieldsByCoordinate) {
        for (GraphQLType type : subgraph.schema().getAllTypesAsList()) {
            if (type instanceof GraphQLObjectType objectType) {
                String typeName = objectType.getName();
                if (BUILT_IN_TYPES.contains(typeName)) {
                    continue;
                }

                for (GraphQLFieldDefinition field : objectType.getFieldDefinitions()) {
                    String coordinate = typeName + "." + field.getName();
                    String overrideFrom = getOverrideFrom(field);

                    fieldsByCoordinate
                        .computeIfAbsent(coordinate, k -> new ArrayList<>())
                        .add(new FieldOverrideInfo(subgraph.name(), field, overrideFrom));
                }
            }
        }
    }

    private String getOverrideFrom(GraphQLFieldDefinition field) {
        if (!field.hasAppliedDirective(OVERRIDE)) {
            return null;
        }

        GraphQLAppliedDirective directive = field.getAppliedDirective(OVERRIDE);
        if (directive == null) {
            return null;
        }

        GraphQLAppliedDirectiveArgument fromArg = directive.getArgument("from");
        if (fromArg == null) {
            return null;
        }

        Object value = fromArg.getValue();
        if (value instanceof String stringValue) {
            return stringValue;
        }

        return null;
    }

    private record FieldOverrideInfo(String schemaName, GraphQLFieldDefinition field, String overrideFrom) {}
}
