package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates that input fields with the same name across subgraphs have mergeable types.
 * Types are mergeable if they have the same base named type (ignoring nullability).
 *
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Input-Field-Types-Not-Mergeable
 */
public final class InputFieldTypesNotMergeableRule implements ValidationRule {

    private static final String CODE = "INPUT_FIELD_TYPES_NOT_MERGEABLE";

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
        return "InputFieldTypesNotMergeableRule";
    }

    @Override
    public ValidationResult validate(List<Subgraph> subgraphs) {
        ValidationResult.Builder builder = ValidationResult.builder();

        // Collect all input fields by coordinate across all subgraphs
        Map<String, List<InputFieldInfo>> fieldsByCoordinate = new LinkedHashMap<>();

        for (Subgraph subgraph : subgraphs) {
            for (GraphQLType type : subgraph.schema().getAllTypesAsList()) {
                if (type instanceof GraphQLInputObjectType inputType) {
                    collectInputFields(inputType, subgraph.name(), fieldsByCoordinate);
                }
            }
        }

        // Check for type conflicts
        for (Map.Entry<String, List<InputFieldInfo>> entry : fieldsByCoordinate.entrySet()) {
            String coordinate = entry.getKey();
            List<InputFieldInfo> fieldInfos = entry.getValue();

            if (fieldInfos.size() < 2) {
                continue;
            }

            InputFieldInfo first = fieldInfos.get(0);
            for (int i = 1; i < fieldInfos.size(); i++) {
                InputFieldInfo current = fieldInfos.get(i);
                if (!areSameTypeShape(first.fieldType, current.fieldType)) {
                    String message = String.format(
                        "Input field '%s' has incompatible types across schemas: '%s' in '%s' vs '%s' in '%s'.",
                        coordinate,
                        GraphQLTypeUtil.simplePrint(first.fieldType), first.schemaName,
                        GraphQLTypeUtil.simplePrint(current.fieldType), current.schemaName
                    );
                    builder.addError(CODE, message, coordinate, null);
                    break;
                }
            }
        }

        return builder.build();
    }

    private void collectInputFields(GraphQLInputObjectType type, String schemaName,
                                    Map<String, List<InputFieldInfo>> fieldsByCoordinate) {
        String typeName = type.getName();
        if (BUILT_IN_TYPES.contains(typeName)) {
            return;
        }

        for (GraphQLInputObjectField field : type.getFieldDefinitions()) {
            String coordinate = typeName + "." + field.getName();
            fieldsByCoordinate
                .computeIfAbsent(coordinate, k -> new ArrayList<>())
                .add(new InputFieldInfo(schemaName, field.getType()));
        }
    }

    private boolean areSameTypeShape(GraphQLInputType typeA, GraphQLInputType typeB) {
        TypeShape shapeA = extractTypeShape(typeA);
        TypeShape shapeB = extractTypeShape(typeB);
        return shapeA.baseName.equals(shapeB.baseName) && shapeA.listDepth == shapeB.listDepth;
    }

    private TypeShape extractTypeShape(GraphQLType type) {
        int listDepth = 0;
        GraphQLType current = type;

        while (true) {
            if (current instanceof GraphQLNonNull nonNull) {
                current = nonNull.getWrappedType();
            } else if (current instanceof GraphQLList list) {
                listDepth++;
                current = list.getWrappedType();
            } else {
                break;
            }
        }

        String baseName = current instanceof GraphQLNamedType named ? named.getName() : "unknown";
        return new TypeShape(baseName, listDepth);
    }

    private record InputFieldInfo(String schemaName, GraphQLInputType fieldType) {}
    private record TypeShape(String baseName, int listDepth) {}
}
