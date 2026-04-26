package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.feddi.federation.engine.compose.FederationDirectives.INACCESSIBLE;

/**
 * Validates that required (non-null) input fields exist in all schemas defining the input type.
 *
 * Input types are merged by intersection. If a field is required (non-null) in any schema,
 * it must exist in all schemas that define that input type, otherwise the merged type
 * would be inconsistent.
 *
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Input-With-Missing-Required-Fields
 */
public final class InputWithMissingRequiredFieldsRule implements ValidationRule {

    private static final String CODE = "INPUT_WITH_MISSING_REQUIRED_FIELDS";

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
        return "InputWithMissingRequiredFieldsRule";
    }

    @Override
    public ValidationResult validate(List<Subgraph> subgraphs) {
        ValidationResult.Builder builder = ValidationResult.builder();

        // Collect all input types by name across all subgraphs
        Map<String, List<InputTypeInfo>> inputTypesByName = new LinkedHashMap<>();

        for (Subgraph subgraph : subgraphs) {
            for (GraphQLType type : subgraph.schema().getAllTypesAsList()) {
                if (type instanceof GraphQLInputObjectType inputType) {
                    String typeName = inputType.getName();
                    if (BUILT_IN_TYPES.contains(typeName)) {
                        continue;
                    }
                    // Skip @inaccessible types
                    if (inputType.hasAppliedDirective(INACCESSIBLE)) {
                        continue;
                    }
                    inputTypesByName
                        .computeIfAbsent(typeName, k -> new ArrayList<>())
                        .add(new InputTypeInfo(subgraph.name(), inputType));
                }
            }
        }

        // Check each input type that appears in multiple schemas
        for (Map.Entry<String, List<InputTypeInfo>> entry : inputTypesByName.entrySet()) {
            String typeName = entry.getKey();
            List<InputTypeInfo> typeInfos = entry.getValue();

            if (typeInfos.size() < 2) {
                continue; // Only one definition, no conflict possible
            }

            // Find all required fields (non-null in at least one schema, not @inaccessible)
            Set<String> requiredFields = new LinkedHashSet<>();
            Map<String, String> requiredFieldSource = new HashMap<>(); // field -> schema that requires it

            for (InputTypeInfo info : typeInfos) {
                for (GraphQLInputObjectField field : info.type.getFieldDefinitions()) {
                    if (field.hasAppliedDirective(INACCESSIBLE)) {
                        continue;
                    }
                    if (GraphQLTypeUtil.isNonNull(field.getType())) {
                        if (!requiredFields.contains(field.getName())) {
                            requiredFields.add(field.getName());
                            requiredFieldSource.put(field.getName(), info.schemaName);
                        }
                    }
                }
            }

            // Check each schema has all required fields
            for (InputTypeInfo info : typeInfos) {
                Set<String> fieldsInSchema = new HashSet<>();
                for (GraphQLInputObjectField field : info.type.getFieldDefinitions()) {
                    fieldsInSchema.add(field.getName());
                }

                for (String requiredField : requiredFields) {
                    if (!fieldsInSchema.contains(requiredField)) {
                        String message = String.format(
                            "Input type '%s' in schema '%s' is missing required field '%s' " +
                            "which is non-null in schema '%s'.",
                            typeName, info.schemaName, requiredField,
                            requiredFieldSource.get(requiredField)
                        );
                        builder.addError(CODE, message, typeName, info.schemaName);
                    }
                }
            }
        }

        return builder.build();
    }

    private record InputTypeInfo(String schemaName, GraphQLInputObjectType type) {}
}
