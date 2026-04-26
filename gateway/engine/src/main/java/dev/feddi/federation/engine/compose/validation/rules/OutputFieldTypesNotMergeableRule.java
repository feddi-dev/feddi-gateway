package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.feddi.federation.engine.compose.FederationDirectives.EXTERNAL;
import static dev.feddi.federation.engine.compose.FederationDirectives.INTERNAL;
import static dev.feddi.federation.engine.compose.FederationDirectives.SHAREABLE;

/**
 * Validates that output fields with the same name across subgraphs have mergeable types.
 * Types are mergeable if they have the same base named type (ignoring nullability).
 *
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Output-Field-Types-Not-Mergeable
 */
public final class OutputFieldTypesNotMergeableRule implements ValidationRule {

    private static final String CODE = "OUTPUT_FIELD_TYPES_NOT_MERGEABLE";

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
        return "OutputFieldTypesNotMergeableRule";
    }

    @Override
    public ValidationResult validate(List<Subgraph> subgraphs) {
        ValidationResult.Builder builder = ValidationResult.builder();

        // Collect all output types by name across all subgraphs
        Map<String, List<TypeFieldInfo>> typeFieldsByName = new LinkedHashMap<>();

        for (Subgraph subgraph : subgraphs) {
            for (GraphQLType type : subgraph.schema().getAllTypesAsList()) {
                if (type instanceof GraphQLObjectType objType) {
                    collectFields(objType, subgraph.name(), typeFieldsByName);
                } else if (type instanceof GraphQLInterfaceType ifaceType) {
                    collectFields(ifaceType, subgraph.name(), typeFieldsByName);
                }
            }
        }

        // Check for type conflicts
        for (Map.Entry<String, List<TypeFieldInfo>> entry : typeFieldsByName.entrySet()) {
            String coordinate = entry.getKey();
            List<TypeFieldInfo> fieldInfos = entry.getValue();

            if (fieldInfos.size() < 2) {
                continue; // Only one definition, no conflict possible
            }

            // Check all pairs for mergeability
            TypeFieldInfo first = fieldInfos.get(0);
            for (int i = 1; i < fieldInfos.size(); i++) {
                TypeFieldInfo current = fieldInfos.get(i);
                if (!areSameTypeShape(first.fieldType, current.fieldType)) {
                    String message = String.format(
                        "Field '%s' has incompatible types across schemas: '%s' in '%s' vs '%s' in '%s'.",
                        coordinate,
                        GraphQLTypeUtil.simplePrint(first.fieldType), first.schemaName,
                        GraphQLTypeUtil.simplePrint(current.fieldType), current.schemaName
                    );
                    builder.addError(CODE, message, coordinate, null);
                    break; // One error per field is enough
                }
            }
        }

        return builder.build();
    }

    private void collectFields(GraphQLFieldsContainer type, String schemaName,
                               Map<String, List<TypeFieldInfo>> typeFieldsByName) {
        String typeName = ((GraphQLNamedType) type).getName();
        if (BUILT_IN_TYPES.contains(typeName)) {
            return;
        }

        for (GraphQLFieldDefinition field : type.getFieldDefinitions()) {
            // Skip @internal fields - they don't participate in schema merging
            if (field.hasAppliedDirective(INTERNAL)) {
                continue;
            }
            // Skip @shareable fields - they have special merge semantics that allow
            // different types (e.g., concrete type vs union containing that type)
            if (field.hasAppliedDirective(SHAREABLE)) {
                continue;
            }
            // Skip @external fields - they reference fields from other schemas
            if (field.hasAppliedDirective(EXTERNAL)) {
                continue;
            }

            String coordinate = typeName + "." + field.getName();
            typeFieldsByName
                .computeIfAbsent(coordinate, k -> new ArrayList<>())
                .add(new TypeFieldInfo(schemaName, field.getType()));
        }
    }

    /**
     * Checks if two types have the same shape (same base type, ignoring nullability).
     */
    private boolean areSameTypeShape(GraphQLOutputType typeA, GraphQLOutputType typeB) {
        // Unwrap both to get base types, tracking list depth
        TypeShape shapeA = extractTypeShape(typeA);
        TypeShape shapeB = extractTypeShape(typeB);

        // Same named type and same list depth
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

    private record TypeFieldInfo(String schemaName, GraphQLOutputType fieldType) {}
    private record TypeShape(String baseName, int listDepth) {}
}
