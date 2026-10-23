package dev.feddi.federation.engine.planner;

import dev.feddi.federation.engine.Constants;
import dev.feddi.federation.engine.executor.Executor;
import dev.feddi.federation.engine.graph.Graph;
import dev.feddi.federation.engine.graph.Node;
import graphql.language.Field;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Finds, for each lookup step, the objects other steps fetch from the same subgraph with every field the lookup
 * selects: a list such as {@code topProducts { upc name price }} answers a later {@code product(upc) { name price }}
 * for the products in it. Within one request, a subgraph resolves the same field of the same entity to the same
 * value, whichever field led to the entity.
 *
 * <p>Conservative: the other step is a root step, the lookup selects leaf fields without arguments or directives,
 * and the path to the objects in the other step's operation has object types only (no interface, union or fragment
 * on the way), so that every object there has the lookup's type.
 */
final class ResultReuse {

    private ResultReuse() {
    }

    static ExecutionPlan withReuseSources(ExecutionPlan plan, Map<Integer, String> lookupTypes, Graph graph) {
        Map<Integer, List<ExecutionPlan.ReuseSource>> sources = new HashMap<>();
        for (ExecutionStep lookup : plan.steps()) {
            String type = lookupTypes.get(lookup.id());
            Map<String, String> fields = type == null || !isQuery(lookup) ? null : leafFields(lookup.operation());
            if (fields == null || fields.isEmpty()) {
                continue;
            }
            List<ExecutionPlan.ReuseSource> found = new ArrayList<>();
            for (ExecutionStep other : plan.steps()) {
                if (other.id() != lookup.id() && other.subgraph().equals(lookup.subgraph()) && isQuery(other)
                    && !Executor.INTROSPECTION_SUBGRAPH.equals(other.subgraph())) {
                    collect(other, type, fields, graph, found);
                }
            }
            if (!found.isEmpty()) {
                sources.put(lookup.id(), found);
            }
        }
        return sources.isEmpty() ? plan : new ExecutionPlan(plan.steps(), sources);
    }

    private static boolean isQuery(ExecutionStep step) {
        return step.operation().getOperation() == OperationDefinition.Operation.QUERY;
    }

    /** The fields the lookup selects (response key to field name), if all are plain leaves; null otherwise. */
    private static @Nullable Map<String, String> leafFields(OperationDefinition operation) {
        Field lookupField = LookupStepMerger.lookupField(operation);
        if (lookupField == null || lookupField.getSelectionSet() == null) {
            return null;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (Selection<?> selection : lookupField.getSelectionSet().getSelections()) {
            if (!(selection instanceof Field field) || !plain(field) || field.getSelectionSet() != null) {
                return null;
            }
            fields.put(field.getResultKey(), field.getName());
        }
        return fields;
    }

    private static boolean plain(Field field) {
        return field.getArguments().isEmpty() && field.getDirectives().isEmpty();
    }

    /** Adds the positions in the other step's operation that select the lookup's fields on objects of its type. */
    private static void collect(ExecutionStep other, String type,
                                Map<String, String> fields, Graph graph, List<ExecutionPlan.ReuseSource> found) {
        // Root steps only: a lookup step's objects are merged into the entities along its entity path, where
        // objects of other types may be, too
        if (other.repeatedExecution() || !other.isRoot()) {
            return;
        }
        SelectionSet start = other.operation().getSelectionSet();
        String startType = Constants.QUERY;
        List<String> path = List.of();
        walk(start, startType, path, other, type, fields, graph, found);
    }

    private static void walk(@Nullable SelectionSet selectionSet, String currentType, List<String> path,
                             ExecutionStep other, String type, Map<String, String> fields, Graph graph,
                             List<ExecutionPlan.ReuseSource> found) {
        if (selectionSet == null) {
            return;
        }
        if (currentType.equals(type) && !path.isEmpty()) {
            Map<String, String> mapped = mapFields(selectionSet, fields);
            if (mapped != null) {
                found.add(new ExecutionPlan.ReuseSource(other.id(), path, mapped));
            }
        }
        for (Selection<?> selection : selectionSet.getSelections()) {
            if (!(selection instanceof Field field) || field.getSelectionSet() == null
                || !field.getDirectives().isEmpty()) {
                continue;
            }
            Optional<String> fieldType = graph.fieldEdgesFrom(new Node(currentType, other.subgraph()))
                .filter(edge -> edge.fieldName().equals(field.getName()))
                .map(edge -> edge.target().typeName())
                .findFirst();
            if (fieldType.isEmpty() || graph.isAbstract(fieldType.get())) {
                continue;
            }
            List<String> fieldPath = new ArrayList<>(path);
            fieldPath.add(field.getResultKey());
            walk(field.getSelectionSet(), fieldType.get(), fieldPath, other, type, fields, graph, found);
        }
    }

    /** The lookup's response keys with the response keys of the same fields here, if all are selected here. */
    private static @Nullable Map<String, String> mapFields(SelectionSet selectionSet, Map<String, String> fields) {
        Map<String, String> mapped = new LinkedHashMap<>();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            String responseKey = null;
            for (Selection<?> selection : selectionSet.getSelections()) {
                if (selection instanceof Field candidate && candidate.getName().equals(field.getValue())
                    && plain(candidate) && candidate.getSelectionSet() == null) {
                    responseKey = candidate.getResultKey();
                    break;
                }
            }
            if (responseKey == null) {
                return null;
            }
            mapped.put(field.getKey(), responseKey);
        }
        return mapped;
    }
}
