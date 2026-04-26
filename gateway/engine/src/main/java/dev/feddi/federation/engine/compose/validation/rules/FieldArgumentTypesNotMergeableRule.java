package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.feddi.federation.engine.compose.FederationDirectives.INTERNAL;

/**
 * Validates that field arguments with the same name across subgraphs have mergeable types.
 * Types are mergeable if they have the same base named type (ignoring nullability).
 *
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Field-Argument-Types-Not-Mergeable
 */
public final class FieldArgumentTypesNotMergeableRule implements ValidationRule {

    private static final String CODE = "FIELD_ARGUMENT_TYPES_NOT_MERGEABLE";

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
        return "FieldArgumentTypesNotMergeableRule";
    }

    @Override
    public ValidationResult validate(List<Subgraph> subgraphs) {
        ValidationResult.Builder builder = ValidationResult.builder();

        // Collect all field arguments by coordinate across all subgraphs
        Map<String, List<ArgumentInfo>> argumentsByCoordinate = new LinkedHashMap<>();

        for (Subgraph subgraph : subgraphs) {
            for (GraphQLType type : subgraph.schema().getAllTypesAsList()) {
                if (type instanceof GraphQLObjectType objType) {
                    collectFieldArguments(objType, subgraph.name(), argumentsByCoordinate);
                } else if (type instanceof GraphQLInterfaceType ifaceType) {
                    collectFieldArguments(ifaceType, subgraph.name(), argumentsByCoordinate);
                }
            }
        }

        // Check for argument type conflicts
        for (Map.Entry<String, List<ArgumentInfo>> entry : argumentsByCoordinate.entrySet()) {
            String coordinate = entry.getKey();
            List<ArgumentInfo> argInfos = entry.getValue();

            if (argInfos.size() < 2) {
                continue;
            }

            ArgumentInfo first = argInfos.get(0);
            for (int i = 1; i < argInfos.size(); i++) {
                ArgumentInfo current = argInfos.get(i);
                if (!areSameTypeShape(first.argType, current.argType)) {
                    String message = String.format(
                        "Argument '%s' has incompatible types across schemas: '%s' in '%s' vs '%s' in '%s'.",
                        coordinate,
                        GraphQLTypeUtil.simplePrint(first.argType), first.schemaName,
                        GraphQLTypeUtil.simplePrint(current.argType), current.schemaName
                    );
                    builder.addError(CODE, message, coordinate, null);
                    break;
                }
            }
        }

        return builder.build();
    }

    private void collectFieldArguments(GraphQLFieldsContainer type, String schemaName,
                                       Map<String, List<ArgumentInfo>> argumentsByCoordinate) {
        String typeName = ((GraphQLNamedType) type).getName();
        if (BUILT_IN_TYPES.contains(typeName)) {
            return;
        }

        for (GraphQLFieldDefinition field : type.getFieldDefinitions()) {
            // Skip @internal fields - they don't participate in schema merging
            if (field.hasAppliedDirective(INTERNAL)) {
                continue;
            }
            for (GraphQLArgument arg : field.getArguments()) {
                String coordinate = typeName + "." + field.getName() + "(" + arg.getName() + ":)";
                argumentsByCoordinate
                    .computeIfAbsent(coordinate, k -> new ArrayList<>())
                    .add(new ArgumentInfo(schemaName, arg.getType()));
            }
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

    private record ArgumentInfo(String schemaName, GraphQLInputType argType) {}
    private record TypeShape(String baseName, int listDepth) {}
}
