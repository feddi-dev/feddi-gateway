package dev.feddi.federation.engine.executor;

import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.language.Field;
import graphql.language.InlineFragment;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import graphql.schema.GraphQLCompositeType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Propagates nulls in non-null positions to the nearest nullable parent, as the GraphQL spec describes in "Handling
 * Execution Errors". The executor assembles the response from subgraph results, so a non-null field can end up
 * null: a subgraph returned null for it (with an error), or its fetch was skipped because a non-null key or
 * {@code @require} value was null. A null without an error at or below its path gets one.
 *
 * <p>Works on a normalized operation (named fragments inlined), like {@link ResponseFieldOrder}. Only fields that
 * are present in the data are checked: a field of an inline fragment whose type condition does not apply to an
 * object is legitimately absent. Objects are completed in place.
 */
public final class NonNullPropagation {

    /** A value that is null in a non-null position: the nearest nullable parent becomes null. */
    private static final Object INVALID = new Object();

    private final GraphQLSchema schema;
    private final List<GraphQLError> errors;
    private final List<GraphQLError> added = new ArrayList<>();

    private NonNullPropagation(GraphQLSchema schema, List<GraphQLError> errors) {
        this.schema = schema;
        this.errors = errors;
    }

    /**
     * Returns the result with nulls propagated and an error for each null that has none; the result itself if
     * nothing changes.
     */
    public static ExecutionResult apply(GraphQLSchema schema, OperationDefinition operation, ExecutionResult result) {
        if (!(result.getData() instanceof Map<?, ?> data)) {
            return result;
        }
        GraphQLCompositeType rootType = switch (operation.getOperation()) {
            case QUERY -> schema.getQueryType();
            case MUTATION -> schema.getMutationType();
            case SUBSCRIPTION -> schema.getSubscriptionType();
        };
        if (rootType == null) {
            return result;
        }
        NonNullPropagation propagation = new NonNullPropagation(schema, result.getErrors());
        @SuppressWarnings("unchecked")
        Object completed = propagation.completeObject(rootType, List.of(operation.getSelectionSet()),
            (Map<String, Object>) data, new ArrayList<>());
        if (propagation.added.isEmpty() && completed != INVALID) {
            return result;
        }
        List<GraphQLError> allErrors = new ArrayList<>(result.getErrors());
        allErrors.addAll(propagation.added);
        return ExecutionResultImpl.newExecutionResult()
            .from(result)
            .data(completed == INVALID ? null : completed)
            .errors(allErrors)
            .build();
    }

    /** A selected field: its definition, the type that defines it and the selection sets of all its occurrences. */
    private record SelectedField(GraphQLFieldDefinition definition, String parentType, List<SelectionSet> selections) {
    }

    /**
     * Completes an object in place; {@link #INVALID} if one of its non-null fields is.
     */
    private Object completeObject(GraphQLCompositeType type, List<SelectionSet> selectionSets,
                                  Map<String, Object> object, List<Object> path) {
        Map<String, SelectedField> fields = new LinkedHashMap<>();
        for (SelectionSet selectionSet : selectionSets) {
            collect(type, selectionSet, fields);
        }
        for (Map.Entry<String, SelectedField> entry : fields.entrySet()) {
            String key = entry.getKey();
            if (!object.containsKey(key)) {
                continue;
            }
            SelectedField field = entry.getValue();
            path.add(key);
            Object value = complete(field.definition().getType(), field.selections(), object.get(key), path,
                field.parentType() + "." + field.definition().getName());
            path.remove(path.size() - 1);
            if (value == INVALID) {
                return INVALID;
            }
            object.put(key, value);
        }
        return object;
    }

    /**
     * Collects the selected fields by response key. Fields of an inline fragment are looked up on its type
     * condition; fields that the type does not define (introspection fields, the fields of a union) are not
     * checked.
     */
    private void collect(GraphQLCompositeType type, SelectionSet selectionSet, Map<String, SelectedField> fields) {
        for (Selection<?> selection : selectionSet.getSelections()) {
            if (selection instanceof Field field) {
                GraphQLFieldDefinition definition = type instanceof GraphQLFieldsContainer container
                    ? container.getFieldDefinition(field.getName()) : null;
                if (definition == null) {
                    continue;
                }
                SelectedField selected = fields.computeIfAbsent(field.getResultKey(),
                    key -> new SelectedField(definition, type.getName(), new ArrayList<>()));
                if (field.getSelectionSet() != null) {
                    selected.selections().add(field.getSelectionSet());
                }
            } else if (selection instanceof InlineFragment fragment) {
                GraphQLCompositeType fragmentType = type;
                if (fragment.getTypeCondition() != null
                    && schema.getType(fragment.getTypeCondition().getName()) instanceof GraphQLCompositeType c) {
                    fragmentType = c;
                }
                collect(fragmentType, fragment.getSelectionSet(), fields);
            }
        }
    }

    /**
     * Completes a value in a position of a type: {@link #INVALID} if it is null (or invalid) in a non-null
     * position, with an error unless one exists at or below the path; null for an invalid value in a nullable
     * position.
     */
    private Object complete(GraphQLOutputType type, List<SelectionSet> selections, Object value, List<Object> path,
                            String coordinate) {
        if (type instanceof GraphQLNonNull nonNull) {
            Object completed = value == null ? INVALID
                : completeInner((GraphQLOutputType) nonNull.getWrappedType(), selections, value, path, coordinate);
            if (completed == INVALID) {
                reportNull(path, coordinate);
            }
            return completed;
        }
        if (value == null) {
            return null;
        }
        Object completed = completeInner(type, selections, value, path, coordinate);
        return completed == INVALID ? null : completed;
    }

    /** Completes a non-null value of a nullable type: a list's items, an object's fields, or a leaf as it is. */
    private Object completeInner(GraphQLOutputType type, List<SelectionSet> selections, Object value,
                                 List<Object> path, String coordinate) {
        if (type instanceof GraphQLList list && value instanceof List<?> items) {
            List<Object> completed = new ArrayList<>(items.size());
            for (int i = 0; i < items.size(); i++) {
                path.add(i);
                Object item = complete((GraphQLOutputType) list.getWrappedType(), selections, items.get(i), path,
                    coordinate);
                path.remove(path.size() - 1);
                if (item == INVALID) {
                    return INVALID;
                }
                completed.add(item);
            }
            return completed;
        }
        if (type instanceof GraphQLCompositeType composite && value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> object = (Map<String, Object>) map;
            return completeObject(composite, selections, object, path);
        }
        return value;
    }

    private void reportNull(List<Object> path, String coordinate) {
        if (errors.stream().anyMatch(error -> startsWith(error.getPath(), path))
            || added.stream().anyMatch(error -> startsWith(error.getPath(), path))) {
            return;
        }
        added.add(GraphqlErrorBuilder.newError()
            .message("Cannot return null for non-nullable field " + coordinate + ".")
            .path(List.copyOf(path))
            .build());
    }

    private static boolean startsWith(List<Object> errorPath, List<Object> path) {
        return errorPath != null && errorPath.size() >= path.size()
            && errorPath.subList(0, path.size()).equals(path);
    }
}
