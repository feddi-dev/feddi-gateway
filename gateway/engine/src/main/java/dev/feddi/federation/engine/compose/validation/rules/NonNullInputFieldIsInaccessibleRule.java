package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.PostMergeValidationRule;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.feddi.federation.engine.compose.FederationDirectives.INACCESSIBLE;

/**
 * Validates that non-null input fields from source schemas exist in the composed schema.
 *
 * When an input field is declared as non-null in any source schema, queries or mutations
 * that reference this field must provide a value for it. If the field is then marked as
 * @inaccessible or removed during schema composition, the final schema would still implicitly
 * demand a value for a field that no longer exists in the composed schema.
 *
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Non-Null-Input-Fields-cannot-be-inaccessible
 */
public final class NonNullInputFieldIsInaccessibleRule implements PostMergeValidationRule {

    private static final String CODE = "NON_NULL_INPUT_FIELD_IS_INACCESSIBLE";

    private static final Set<String> BUILT_IN_TYPES = Set.of(
        "String", "Int", "Float", "Boolean", "ID",
        "__Schema", "__Type", "__Field", "__InputValue", "__EnumValue",
        "__TypeKind", "__Directive", "__DirectiveLocation"
    );

    @Override
    public String name() {
        return "NonNullInputFieldIsInaccessibleRule";
    }

    @Override
    public ValidationResult validate(GraphQLSchema mergedSchema, List<Subgraph> subgraphs) {
        ValidationResult.Builder builder = ValidationResult.builder();

        // Collect all non-null input fields from source schemas
        // Key: "TypeName.fieldName", Value: schema names where it's non-null
        Map<String, List<String>> nonNullFieldSources = new LinkedHashMap<>();

        for (Subgraph subgraph : subgraphs) {
            for (GraphQLType type : subgraph.schema().getAllTypesAsList()) {
                if (type instanceof GraphQLInputObjectType inputType) {
                    String typeName = inputType.getName();
                    if (BUILT_IN_TYPES.contains(typeName)) {
                        continue;
                    }

                    for (GraphQLInputObjectField field : inputType.getFieldDefinitions()) {
                        if (GraphQLTypeUtil.isNonNull(field.getType())) {
                            String coordinate = typeName + "." + field.getName();
                            nonNullFieldSources
                                .computeIfAbsent(coordinate, k -> new ArrayList<>())
                                .add(subgraph.name());
                        }
                    }
                }
            }
        }

        // Check each non-null field exists and is accessible in the merged schema
        for (Map.Entry<String, List<String>> entry : nonNullFieldSources.entrySet()) {
            String coordinate = entry.getKey();
            List<String> sourceSchemas = entry.getValue();

            String[] parts = coordinate.split("\\.", 2);
            String typeName = parts[0];
            String fieldName = parts[1];

            // Check if the type exists in the merged schema
            GraphQLType mergedType = mergedSchema.getType(typeName);
            if (mergedType == null) {
                // Type doesn't exist - this is an error
                String message = String.format(
                    "Non-null input field '%s' from schema(s) '%s' is not present in the composed schema " +
                    "because the input type '%s' was not included.",
                    coordinate, String.join(", ", sourceSchemas), typeName
                );
                builder.addError(CODE, message, coordinate, sourceSchemas.get(0), null);
                continue;
            }

            if (!(mergedType instanceof GraphQLInputObjectType mergedInputType)) {
                // Type kind changed - different error, skip
                continue;
            }

            // Check if the type is inaccessible
            if (mergedInputType.hasAppliedDirective(INACCESSIBLE)) {
                String message = String.format(
                    "Non-null input field '%s' from schema(s) '%s' is not accessible in the composed schema " +
                    "because the input type '%s' is marked @inaccessible.",
                    coordinate, String.join(", ", sourceSchemas), typeName
                );
                builder.addError(CODE, message, coordinate, sourceSchemas.get(0), null);
                continue;
            }

            // Check if the field exists in the merged type
            GraphQLInputObjectField mergedField = mergedInputType.getFieldDefinition(fieldName);
            if (mergedField == null) {
                // Field doesn't exist in the merged schema
                String message = String.format(
                    "Non-null input field '%s' from schema(s) '%s' is not present in the composed schema.",
                    coordinate, String.join(", ", sourceSchemas)
                );
                builder.addError(CODE, message, coordinate, sourceSchemas.get(0), null);
                continue;
            }

            // Check if the field is inaccessible
            if (mergedField.hasAppliedDirective(INACCESSIBLE)) {
                String message = String.format(
                    "Non-null input field '%s' from schema(s) '%s' is marked @inaccessible in the composed schema.",
                    coordinate, String.join(", ", sourceSchemas)
                );
                builder.addError(CODE, message, coordinate, sourceSchemas.get(0), null);
            }
        }

        return builder.build();
    }
}
