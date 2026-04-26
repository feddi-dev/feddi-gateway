package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import graphql.language.ArrayValue;
import graphql.language.EnumValue;
import graphql.language.ObjectField;
import graphql.language.ObjectValue;
import graphql.language.Value;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLEnumValueDefinition;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.feddi.federation.engine.compose.FederationDirectives.INACCESSIBLE;

/**
 * Validates that default values do not reference inaccessible enum values.
 *
 * Output field arguments and input fields must only use enum values as their
 * default value when the enum value is not annotated with @inaccessible.
 *
 * Note: This is implemented as a PRE_MERGE rule because @inaccessible enum values
 * are removed during the merge process. Validating at the source schema level
 * catches the same errors while allowing the merge to proceed correctly.
 *
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Enum-Type-Default-Value-Inaccessible
 */
public final class EnumTypeDefaultValueInaccessibleRule implements ValidationRule {

    private static final String CODE = "ENUM_TYPE_DEFAULT_VALUE_INACCESSIBLE";

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
        return "EnumTypeDefaultValueInaccessibleRule";
    }

    @Override
    public ValidationResult validate(List<Subgraph> subgraphs) {
        ValidationResult.Builder builder = ValidationResult.builder();

        for (Subgraph subgraph : subgraphs) {
            validateSubgraph(subgraph, builder);
        }

        return builder.build();
    }

    private void validateSubgraph(Subgraph subgraph, ValidationResult.Builder builder) {
        GraphQLSchema schema = subgraph.schema();
        String schemaName = subgraph.name();

        // Collect all inaccessible enum values
        Map<String, Set<String>> inaccessibleEnumValues = collectInaccessibleEnumValues(schema);

        if (inaccessibleEnumValues.isEmpty()) {
            return; // No inaccessible enum values
        }

        // Check arguments on fields
        for (GraphQLNamedType type : schema.getAllTypesAsList()) {
            if (type instanceof GraphQLObjectType objectType) {
                checkFieldArguments(objectType.getName(), objectType.getFieldDefinitions(),
                    inaccessibleEnumValues, schemaName, builder);
            } else if (type instanceof GraphQLInterfaceType interfaceType) {
                checkFieldArguments(interfaceType.getName(), interfaceType.getFieldDefinitions(),
                    inaccessibleEnumValues, schemaName, builder);
            } else if (type instanceof GraphQLInputObjectType inputType) {
                checkInputFields(inputType, inaccessibleEnumValues, schemaName, builder);
            }
        }
    }

    private void checkFieldArguments(String typeName, List<GraphQLFieldDefinition> fields,
                                     Map<String, Set<String>> inaccessibleEnumValues,
                                     String schemaName, ValidationResult.Builder builder) {
        if (BUILT_IN_TYPES.contains(typeName)) {
            return;
        }

        for (GraphQLFieldDefinition field : fields) {
            for (GraphQLArgument arg : field.getArguments()) {
                if (arg.hasSetDefaultValue()) {
                    Object defaultValue = arg.getArgumentDefaultValue().getValue();
                    String coordinate = typeName + "." + field.getName() + "(" + arg.getName() + ")";
                    validateDefaultValue(defaultValue, arg.getType(), coordinate,
                        inaccessibleEnumValues, schemaName, builder);
                }
            }
        }
    }

    private void checkInputFields(GraphQLInputObjectType inputType,
                                  Map<String, Set<String>> inaccessibleEnumValues,
                                  String schemaName, ValidationResult.Builder builder) {
        if (BUILT_IN_TYPES.contains(inputType.getName())) {
            return;
        }

        for (GraphQLInputObjectField field : inputType.getFieldDefinitions()) {
            if (field.hasSetDefaultValue()) {
                Object defaultValue = field.getInputFieldDefaultValue().getValue();
                String coordinate = inputType.getName() + "." + field.getName();
                validateDefaultValue(defaultValue, field.getType(), coordinate,
                    inaccessibleEnumValues, schemaName, builder);
            }
        }
    }

    private void validateDefaultValue(Object value, GraphQLType type, String coordinate,
                                      Map<String, Set<String>> inaccessibleEnumValues,
                                      String schemaName, ValidationResult.Builder builder) {
        if (value == null) {
            return;
        }

        GraphQLType unwrappedType = GraphQLTypeUtil.unwrapAll(type);

        // Handle enum values
        if (unwrappedType instanceof GraphQLEnumType enumType) {
            String enumTypeName = enumType.getName();
            Set<String> inaccessibleValues = inaccessibleEnumValues.get(enumTypeName);

            if (inaccessibleValues != null) {
                String enumValueName = extractEnumValueName(value);
                if (enumValueName != null && inaccessibleValues.contains(enumValueName)) {
                    String message = String.format(
                        "Default value at '%s' in schema '%s' uses inaccessible enum value '%s.%s'.",
                        coordinate, schemaName, enumTypeName, enumValueName
                    );
                    builder.addError(CODE, message, coordinate, schemaName);
                }
            }
        }

        // Handle lists recursively
        if (value instanceof List<?> listValue) {
            GraphQLType elementType = GraphQLTypeUtil.unwrapNonNull(type);
            if (elementType instanceof GraphQLList listType) {
                for (Object element : listValue) {
                    validateDefaultValue(element, listType.getWrappedType(), coordinate,
                        inaccessibleEnumValues, schemaName, builder);
                }
            }
        }

        // Handle object values recursively
        if (value instanceof Map<?, ?> objectValue && unwrappedType instanceof GraphQLInputObjectType inputType) {
            for (Map.Entry<?, ?> entry : objectValue.entrySet()) {
                String fieldName = entry.getKey().toString();
                GraphQLInputObjectField inputField = inputType.getFieldDefinition(fieldName);
                if (inputField != null) {
                    validateDefaultValue(entry.getValue(), inputField.getType(),
                        coordinate + "." + fieldName, inaccessibleEnumValues, schemaName, builder);
                }
            }
        }

        // Handle AST Value nodes (from graphql-java)
        if (value instanceof EnumValue enumValue) {
            if (unwrappedType instanceof GraphQLEnumType enumType) {
                Set<String> inaccessibleValues = inaccessibleEnumValues.get(enumType.getName());
                if (inaccessibleValues != null && inaccessibleValues.contains(enumValue.getName())) {
                    String message = String.format(
                        "Default value at '%s' in schema '%s' uses inaccessible enum value '%s.%s'.",
                        coordinate, schemaName, enumType.getName(), enumValue.getName()
                    );
                    builder.addError(CODE, message, coordinate, schemaName);
                }
            }
        }

        if (value instanceof ArrayValue arrayValue) {
            GraphQLType elementType = GraphQLTypeUtil.unwrapNonNull(type);
            if (elementType instanceof GraphQLList listType) {
                for (Value<?> element : arrayValue.getValues()) {
                    validateDefaultValue(element, listType.getWrappedType(), coordinate,
                        inaccessibleEnumValues, schemaName, builder);
                }
            }
        }

        if (value instanceof ObjectValue objectValueNode && unwrappedType instanceof GraphQLInputObjectType inputType) {
            for (ObjectField field : objectValueNode.getObjectFields()) {
                GraphQLInputObjectField inputField = inputType.getFieldDefinition(field.getName());
                if (inputField != null) {
                    validateDefaultValue(field.getValue(), inputField.getType(),
                        coordinate + "." + field.getName(), inaccessibleEnumValues, schemaName, builder);
                }
            }
        }
    }

    private String extractEnumValueName(Object value) {
        if (value instanceof EnumValue enumValue) {
            return enumValue.getName();
        }
        if (value instanceof String stringValue) {
            return stringValue;
        }
        return null;
    }

    private Map<String, Set<String>> collectInaccessibleEnumValues(GraphQLSchema schema) {
        Map<String, Set<String>> result = new HashMap<>();

        for (GraphQLNamedType type : schema.getAllTypesAsList()) {
            if (type instanceof GraphQLEnumType enumType) {
                Set<String> inaccessibleValues = new HashSet<>();
                for (GraphQLEnumValueDefinition value : enumType.getValues()) {
                    if (value.hasAppliedDirective(INACCESSIBLE)) {
                        inaccessibleValues.add(value.getName());
                    }
                }
                if (!inaccessibleValues.isEmpty()) {
                    result.put(enumType.getName(), inaccessibleValues);
                }
            }
        }

        return result;
    }
}
