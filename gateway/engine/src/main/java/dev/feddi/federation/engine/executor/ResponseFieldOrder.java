package dev.feddi.federation.engine.executor;

import graphql.language.Field;
import graphql.language.InlineFragment;
import graphql.language.Selection;
import graphql.language.SelectionSet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Puts the fields of response objects into the order of the query's selection set. The executor
 * assembles objects from several subgraph responses, so fields otherwise end up in the order in
 * which the responses were merged. The GraphQL spec ("Serialization Format") asks for the order
 * of the selection set.
 *
 * <p>Works on a normalized operation (named fragments inlined). A field counts at its first
 * occurrence, also across inline fragments. Keys of the object that are not selected (none are
 * expected after the executor removed internal fields) keep their place at the end.
 */
public final class ResponseFieldOrder {

    private ResponseFieldOrder() {
    }

    /**
     * Returns the data with every object's fields in selection order. Lists keep their order;
     * values that are not objects or lists are returned as they are.
     */
    public static Object reorder(SelectionSet selectionSet, Object data) {
        return reorder(List.of(selectionSet), data);
    }

    private static Object reorder(List<SelectionSet> selectionSets, Object data) {
        if (data instanceof Map<?, ?> map) {
            return reorderObject(selectionSets, map);
        }
        if (data instanceof List<?> list) {
            List<Object> reordered = new ArrayList<>(list.size());
            for (Object item : list) {
                reordered.add(reorder(selectionSets, item));
            }
            return reordered;
        }
        return data;
    }

    private static Map<String, Object> reorderObject(List<SelectionSet> selectionSets, Map<?, ?> object) {
        // Response key -> selection sets of all fields with that key (fields can repeat across fragments)
        Map<String, List<SelectionSet>> fields = new LinkedHashMap<>();
        for (SelectionSet selectionSet : selectionSets) {
            collectFields(selectionSet, fields);
        }

        Map<String, Object> reordered = new LinkedHashMap<>(Math.max(4, object.size() * 2));
        for (Map.Entry<String, List<SelectionSet>> field : fields.entrySet()) {
            String key = field.getKey();
            if (object.containsKey(key)) {
                reordered.put(key, reorder(field.getValue(), object.get(key)));
            }
        }
        for (Map.Entry<?, ?> entry : object.entrySet()) {
            reordered.putIfAbsent(String.valueOf(entry.getKey()), entry.getValue());
        }
        return reordered;
    }

    private static void collectFields(SelectionSet selectionSet, Map<String, List<SelectionSet>> fields) {
        if (selectionSet == null) {
            return;
        }
        for (Selection<?> selection : selectionSet.getSelections()) {
            if (selection instanceof Field field) {
                List<SelectionSet> sets = fields.computeIfAbsent(field.getResultKey(), k -> new ArrayList<>());
                if (field.getSelectionSet() != null) {
                    sets.add(field.getSelectionSet());
                }
            } else if (selection instanceof InlineFragment fragment) {
                collectFields(fragment.getSelectionSet(), fields);
            }
        }
    }
}
