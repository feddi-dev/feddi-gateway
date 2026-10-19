package dev.feddi.federation.engine.planner;

import graphql.language.AstPrinter;
import graphql.language.Field;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gives lookup steps that look up the same entities differently one operation: steps of a query that send the
 * same lookup (subgraph, lookup field, arguments, variables) after the same steps, but select different fields
 * (e.g. a product's name below one response path and its price below another), get the lookup with the union of
 * their selections. The executor then runs them as one, with the entities of all of them deduplicated and fetched
 * once.
 *
 * <p>Each step marks the fields it gets from the others as artificial, at its entity path, so that they are not
 * part of the response there. Selections with inline fragments, and fields of the same response key with
 * different arguments, are not merged.
 */
final class LookupStepMerger {

    private LookupStepMerger() {
    }

    private record LookupKey(String subgraph, String lookup, List<Integer> dependsOn) {
    }

    static ExecutionPlan merge(ExecutionPlan plan) {
        Map<LookupKey, List<ExecutionStep>> candidates = new LinkedHashMap<>();
        for (ExecutionStep step : plan.steps()) {
            String lookup = mergeableLookup(step);
            if (lookup != null) {
                candidates.computeIfAbsent(new LookupKey(step.subgraph(), lookup,
                    step.dependsOn().stream().sorted().toList()), key -> new ArrayList<>()).add(step);
            }
        }
        // The response paths the plan writes (requested or internal), without type conditions: an added field must
        // not land on one of them, where its response key may stand for another field
        Set<String> written = new HashSet<>();
        for (ExecutionStep step : plan.steps()) {
            step.requestedFieldPaths().forEach(path -> written.add(withoutTypeConditions(path)));
            step.artificialFieldPaths().forEach(path -> written.add(withoutTypeConditions(path)));
        }
        Map<Integer, ExecutionStep> replaced = new LinkedHashMap<>();
        for (List<ExecutionStep> steps : candidates.values()) {
            Set<String> printed = new HashSet<>();
            steps.forEach(step -> printed.add(AstPrinter.printAstCompact(step.operation())));
            if (steps.size() < 2 || printed.size() < 2) {
                continue;
            }
            SelectionSet merged = mergeSelections(steps.stream()
                .map(step -> lookupField(step.operation()).getSelectionSet()).toList());
            if (merged == null) {
                continue;
            }
            OperationDefinition operation = withLookupSelection(steps.get(0).operation(), merged);
            Set<String> mergedPaths = paths(merged, "");
            Map<Integer, Set<String>> addedPaths = new LinkedHashMap<>();
            boolean collides = false;
            for (ExecutionStep step : steps) {
                Set<String> added = new HashSet<>(mergedPaths);
                added.removeAll(paths(lookupField(step.operation()).getSelectionSet(), ""));
                String prefix = String.join(".", step.entityPath());
                Set<String> absolute = new HashSet<>();
                added.forEach(path -> absolute.add(prefix.isEmpty() ? path : prefix + "." + path));
                collides |= absolute.stream().anyMatch(written::contains);
                addedPaths.put(step.id(), absolute);
            }
            if (collides) {
                continue;
            }
            for (ExecutionStep step : steps) {
                Set<String> artificial = new HashSet<>(step.artificialFieldPaths());
                artificial.addAll(addedPaths.get(step.id()));
                replaced.put(step.id(), step.withOperation(operation, artificial));
            }
        }
        if (replaced.isEmpty()) {
            return plan;
        }
        return new ExecutionPlan(plan.steps().stream().map(step -> replaced.getOrDefault(step.id(), step)).toList());
    }

    /**
     * The step's lookup without the lookup field's selection, if it is a lookup of a query on a plain path: the
     * field with arguments below argument-less wrapper fields, selecting fields only.
     */
    private static @Nullable String mergeableLookup(ExecutionStep step) {
        if (!step.repeatedExecution() || step.entityPath() == null
            || step.operation().getOperation() != OperationDefinition.Operation.QUERY) {
            return null;
        }
        Field lookup = lookupField(step.operation());
        if (lookup == null || lookup.getSelectionSet() == null || !fieldsOnly(lookup.getSelectionSet())) {
            return null;
        }
        return AstPrinter.printAstCompact(withLookupSelection(step.operation(), SelectionSet.newSelectionSet().build()));
    }

    /** The lookup field: the first field with arguments, below fields without arguments that select one field. */
    private static @Nullable Field lookupField(OperationDefinition operation) {
        SelectionSet level = operation.getSelectionSet();
        while (level != null && level.getSelections().size() == 1 && level.getSelections().get(0) instanceof Field field) {
            if (!field.getArguments().isEmpty()) {
                return field;
            }
            level = field.getSelectionSet();
        }
        return null;
    }

    /** The operation with the lookup field selecting {@code selection}. */
    private static OperationDefinition withLookupSelection(OperationDefinition operation, SelectionSet selection) {
        return operation.transform(builder -> builder.selectionSet(replaceLookupSelection(operation.getSelectionSet(),
            selection)));
    }

    private static SelectionSet replaceLookupSelection(SelectionSet level, SelectionSet selection) {
        Field field = (Field) level.getSelections().get(0);
        Field replaced = field.getArguments().isEmpty()
            ? field.transform(builder -> builder.selectionSet(replaceLookupSelection(field.getSelectionSet(), selection)))
            : field.transform(builder -> builder.selectionSet(selection));
        return SelectionSet.newSelectionSet().selection(replaced).build();
    }

    private static boolean fieldsOnly(SelectionSet selectionSet) {
        for (Selection<?> selection : selectionSet.getSelections()) {
            if (!(selection instanceof Field field) || !field.getDirectives().isEmpty()
                || field.getSelectionSet() != null && !fieldsOnly(field.getSelectionSet())) {
                return false;
            }
        }
        return true;
    }

    /**
     * The union of selection sets, by response key; null if a response key stands for different fields or
     * arguments.
     */
    private static @Nullable SelectionSet mergeSelections(List<SelectionSet> selectionSets) {
        Map<String, List<Field>> byKey = new LinkedHashMap<>();
        for (SelectionSet selectionSet : selectionSets) {
            for (Selection<?> selection : selectionSet.getSelections()) {
                Field field = (Field) selection;
                byKey.computeIfAbsent(field.getResultKey(), key -> new ArrayList<>()).add(field);
            }
        }
        SelectionSet.Builder merged = SelectionSet.newSelectionSet();
        for (List<Field> fields : byKey.values()) {
            Field first = fields.get(0);
            String arguments = AstPrinter.printAstCompact(Field.newField(first.getName())
                .arguments(first.getArguments()).build());
            List<SelectionSet> subSelections = new ArrayList<>();
            for (Field field : fields) {
                String fieldArguments = AstPrinter.printAstCompact(Field.newField(field.getName())
                    .arguments(field.getArguments()).build());
                if (!field.getName().equals(first.getName()) || !fieldArguments.equals(arguments)
                    || (field.getSelectionSet() == null) != (first.getSelectionSet() == null)) {
                    return null;
                }
                if (field.getSelectionSet() != null) {
                    subSelections.add(field.getSelectionSet());
                }
            }
            if (subSelections.isEmpty()) {
                merged.selection(first);
            } else {
                SelectionSet subSelection = mergeSelections(subSelections);
                if (subSelection == null) {
                    return null;
                }
                merged.selection(first.transform(builder -> builder.selectionSet(subSelection)));
            }
        }
        return merged.build();
    }

    private static String withoutTypeConditions(String path) {
        return path.replaceAll("<[^>]*>", "");
    }

    /** The response paths (dot notation) of a selection set's fields, also nested ones. */
    private static Set<String> paths(SelectionSet selectionSet, String prefix) {
        Set<String> paths = new HashSet<>();
        for (Selection<?> selection : selectionSet.getSelections()) {
            Field field = (Field) selection;
            String path = prefix.isEmpty() ? field.getResultKey() : prefix + "." + field.getResultKey();
            paths.add(path);
            if (field.getSelectionSet() != null) {
                paths.addAll(paths(field.getSelectionSet(), path));
            }
        }
        return paths;
    }
}
