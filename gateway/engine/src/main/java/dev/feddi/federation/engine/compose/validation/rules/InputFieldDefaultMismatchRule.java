package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates that input fields with the same name across schemas have consistent default values.
 *
 * When input fields are merged, their default values must match to avoid ambiguity.
 * If two schemas define the same input field with different defaults, composition fails.
 *
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Input-Field-Default-Mismatch
 */
public final class InputFieldDefaultMismatchRule implements ValidationRule {

    private static final String CODE = "INPUT_FIELD_DEFAULT_MISMATCH";

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
        return "InputFieldDefaultMismatchRule";
    }

    @Override
    public ValidationResult validate(List<Subgraph> subgraphs) {
        ValidationResult.Builder builder = ValidationResult.builder();

        // Collect all input fields by coordinate across all subgraphs
        Map<String, List<FieldInfo>> fieldsByCoordinate = new LinkedHashMap<>();

        for (Subgraph subgraph : subgraphs) {
            for (GraphQLType type : subgraph.schema().getAllTypesAsList()) {
                if (type instanceof GraphQLInputObjectType inputType) {
                    collectFields(inputType, subgraph.name(), fieldsByCoordinate);
                }
            }
        }

        // Check for default value mismatches
        for (Map.Entry<String, List<FieldInfo>> entry : fieldsByCoordinate.entrySet()) {
            String coordinate = entry.getKey();
            List<FieldInfo> fieldInfos = entry.getValue();

            if (fieldInfos.size() < 2) {
                continue; // Only one definition, no mismatch possible
            }

            // Find fields that have default values
            List<FieldInfo> fieldsWithDefaults = fieldInfos.stream()
                .filter(f -> f.hasDefault)
                .toList();

            if (fieldsWithDefaults.size() < 2) {
                continue; // At most one has a default, no conflict
            }

            // Compare all pairs of defaults
            FieldInfo first = fieldsWithDefaults.get(0);
            for (int i = 1; i < fieldsWithDefaults.size(); i++) {
                FieldInfo other = fieldsWithDefaults.get(i);
                if (!defaultValuesEqual(first.defaultValue, other.defaultValue)) {
                    String message = String.format(
                        "Input field '%s' has different default values across schemas: " +
                        "'%s' in schema '%s' vs '%s' in schema '%s'.",
                        coordinate,
                        formatDefaultValue(first.defaultValue), first.schemaName,
                        formatDefaultValue(other.defaultValue), other.schemaName
                    );
                    builder.addError(CODE, message, coordinate, first.schemaName);
                    break; // One error per coordinate is enough
                }
            }
        }

        return builder.build();
    }

    private void collectFields(GraphQLInputObjectType type, String schemaName,
                               Map<String, List<FieldInfo>> fieldsByCoordinate) {
        String typeName = type.getName();
        if (BUILT_IN_TYPES.contains(typeName)) {
            return;
        }

        for (GraphQLInputObjectField field : type.getFieldDefinitions()) {
            String coordinate = typeName + "." + field.getName();
            boolean hasDefault = field.hasSetDefaultValue();
            Object defaultValue = hasDefault ? field.getInputFieldDefaultValue().getValue() : null;

            fieldsByCoordinate
                .computeIfAbsent(coordinate, k -> new ArrayList<>())
                .add(new FieldInfo(schemaName, hasDefault, defaultValue));
        }
    }

    private boolean defaultValuesEqual(Object a, Object b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        // Use string representation for comparison to handle various value types
        return formatDefaultValue(a).equals(formatDefaultValue(b));
    }

    private String formatDefaultValue(Object value) {
        if (value == null) {
            return "null";
        }
        return value.toString();
    }

    private record FieldInfo(String schemaName, boolean hasDefault, Object defaultValue) {}
}
