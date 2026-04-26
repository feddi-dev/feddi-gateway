package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.feddi.federation.engine.compose.FederationDirectives.INTERNAL;
import static dev.feddi.federation.engine.compose.FederationDirectives.REQUIRE;

/**
 * Validates that required arguments are present in all schemas defining a field.
 *
 * When merging a field definition across multiple schemas, any argument that is
 * non-null (required) in one schema must appear in all schemas that define that field.
 * Arguments marked with @require are treated as non-required.
 *
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Field-With-Missing-Required-Arguments
 */
public final class FieldWithMissingRequiredArgumentRule implements ValidationRule {

    private static final String CODE = "FIELD_WITH_MISSING_REQUIRED_ARGUMENT";

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
        return "FieldWithMissingRequiredArgumentRule";
    }

    @Override
    public ValidationResult validate(List<Subgraph> subgraphs) {
        ValidationResult.Builder builder = ValidationResult.builder();

        // Collect all field definitions by type name and field name
        // Key: "TypeName.fieldName" -> List of (schemaName, fieldDefinition)
        Map<String, List<FieldInfo>> fieldsByCoordinate = new LinkedHashMap<>();

        for (Subgraph subgraph : subgraphs) {
            collectFields(subgraph, fieldsByCoordinate);
        }

        // Check each field that appears in multiple schemas
        for (Map.Entry<String, List<FieldInfo>> entry : fieldsByCoordinate.entrySet()) {
            String coordinate = entry.getKey();
            List<FieldInfo> fieldInfos = entry.getValue();

            if (fieldInfos.size() < 2) {
                continue; // Only one definition, no conflict possible
            }

            // Collect all required arguments (non-null without @require)
            // Key: argument name, Value: schema name that requires it
            Map<String, String> requiredArguments = new LinkedHashMap<>();

            for (FieldInfo info : fieldInfos) {
                for (GraphQLArgument arg : info.field.getArguments()) {
                    if (isRequiredArgument(arg)) {
                        String argName = arg.getName();
                        if (!requiredArguments.containsKey(argName)) {
                            requiredArguments.put(argName, info.schemaName);
                        }
                    }
                }
            }

            // Check each field definition has all required arguments
            for (FieldInfo info : fieldInfos) {
                Set<String> argNames = new HashSet<>();
                Map<String, Boolean> argHasRequire = new HashMap<>();

                for (GraphQLArgument arg : info.field.getArguments()) {
                    argNames.add(arg.getName());
                    argHasRequire.put(arg.getName(), arg.hasAppliedDirective(REQUIRE));
                }

                for (Map.Entry<String, String> reqArg : requiredArguments.entrySet()) {
                    String argName = reqArg.getKey();
                    String requiringSchema = reqArg.getValue();

                    if (!argNames.contains(argName)) {
                        // Argument is completely missing
                        String message = String.format(
                            "Field '%s' in schema '%s' is missing required argument '%s' " +
                            "which is non-null in schema '%s'.",
                            coordinate, info.schemaName, argName, requiringSchema
                        );
                        builder.addError(CODE, message, coordinate, info.schemaName);
                    } else if (argHasRequire.get(argName)) {
                        // Argument exists but has @require - that's also invalid
                        // If one schema has it as required (non-null without @require),
                        // other schemas cannot have it with @require
                        String message = String.format(
                            "Field '%s' in schema '%s' has argument '%s' with @require, " +
                            "but it is required (non-null without @require) in schema '%s'.",
                            coordinate, info.schemaName, argName, requiringSchema
                        );
                        builder.addError(CODE, message, coordinate, info.schemaName);
                    }
                }
            }
        }

        return builder.build();
    }

    private void collectFields(Subgraph subgraph, Map<String, List<FieldInfo>> fieldsByCoordinate) {
        for (GraphQLType type : subgraph.schema().getAllTypesAsList()) {
            String typeName = null;
            List<GraphQLFieldDefinition> fields = null;

            if (type instanceof GraphQLObjectType objectType) {
                typeName = objectType.getName();
                fields = objectType.getFieldDefinitions();
            } else if (type instanceof GraphQLInterfaceType interfaceType) {
                typeName = interfaceType.getName();
                fields = interfaceType.getFieldDefinitions();
            }

            if (typeName == null || BUILT_IN_TYPES.contains(typeName)) {
                continue;
            }

            for (GraphQLFieldDefinition field : fields) {
                // Skip @internal fields - they don't participate in schema merging
                if (field.hasAppliedDirective(INTERNAL)) {
                    continue;
                }
                String coordinate = typeName + "." + field.getName();
                fieldsByCoordinate
                    .computeIfAbsent(coordinate, k -> new ArrayList<>())
                    .add(new FieldInfo(subgraph.name(), field));
            }
        }
    }

    private boolean isRequiredArgument(GraphQLArgument arg) {
        // Argument is required if:
        // 1. It has a non-null type
        // 2. It does NOT have @require directive
        return GraphQLTypeUtil.isNonNull(arg.getType())
            && !arg.hasAppliedDirective(REQUIRE);
    }

    private record FieldInfo(String schemaName, GraphQLFieldDefinition field) {}
}
