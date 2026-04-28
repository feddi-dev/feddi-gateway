package dev.feddi.federation.app;

import graphql.language.Document;
import graphql.language.Field;
import graphql.language.FragmentDefinition;
import graphql.language.FragmentSpread;
import graphql.language.InlineFragment;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnmodifiedType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Extracts field coordinates (schema coordinates) from a parsed GraphQL document.
 * Walks the operation AST against the schema to produce coordinates like
 * "Query.me", "User.id", "User.name".
 */
public final class FieldCoordinateExtractor {

    /**
     * Extract all field coordinates referenced in the first operation of the document.
     */
    public static List<String> extract(Document document, GraphQLSchema schema) {
        Set<String> coordinates = new LinkedHashSet<>();

        Map<String, FragmentDefinition> fragments = document.getDefinitionsOfType(FragmentDefinition.class)
                .stream()
                .collect(Collectors.toMap(FragmentDefinition::getName, f -> f));

        OperationDefinition operation = document.getDefinitionsOfType(OperationDefinition.class)
                .stream()
                .findFirst()
                .orElse(null);

        if (operation == null) {
            return List.of();
        }

        GraphQLObjectType rootType = switch (operation.getOperation()) {
            case QUERY -> schema.getQueryType();
            case MUTATION -> schema.getMutationType();
            case SUBSCRIPTION -> schema.getSubscriptionType();
        };

        if (rootType == null) {
            return List.of();
        }

        collectFields(rootType, operation.getSelectionSet(), schema, fragments, coordinates);
        return new ArrayList<>(coordinates);
    }

    private static void collectFields(
            GraphQLObjectType parentType,
            SelectionSet selectionSet,
            GraphQLSchema schema,
            Map<String, FragmentDefinition> fragments,
            Set<String> coordinates) {

        if (selectionSet == null) return;

        for (Selection<?> selection : selectionSet.getSelections()) {
            if (selection instanceof Field field) {
                String coordinate = parentType.getName() + "." + field.getName();
                coordinates.add(coordinate);

                // Recurse into nested selections
                GraphQLFieldDefinition fieldDef = parentType.getFieldDefinition(field.getName());
                if (fieldDef != null && field.getSelectionSet() != null) {
                    GraphQLUnmodifiedType unwrapped = GraphQLTypeUtil.unwrapAll(fieldDef.getType());
                    if (unwrapped instanceof GraphQLObjectType objectType) {
                        collectFields(objectType, field.getSelectionSet(), schema, fragments, coordinates);
                    }
                }
            } else if (selection instanceof InlineFragment inlineFragment) {
                String typeName = inlineFragment.getTypeCondition() != null
                        ? inlineFragment.getTypeCondition().getName() : parentType.getName();
                GraphQLType type = schema.getType(typeName);
                if (type instanceof GraphQLObjectType objectType) {
                    collectFields(objectType, inlineFragment.getSelectionSet(), schema, fragments, coordinates);
                }
            } else if (selection instanceof FragmentSpread fragmentSpread) {
                FragmentDefinition fragment = fragments.get(fragmentSpread.getName());
                if (fragment != null) {
                    GraphQLType type = schema.getType(fragment.getTypeCondition().getName());
                    if (type instanceof GraphQLObjectType objectType) {
                        collectFields(objectType, fragment.getSelectionSet(), schema, fragments, coordinates);
                    }
                }
            }
        }
    }
}
