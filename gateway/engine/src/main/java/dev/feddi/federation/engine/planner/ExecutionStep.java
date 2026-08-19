package dev.feddi.federation.engine.planner;

import dev.feddi.federation.engine.parser.FieldSelectionMap.SelectedValue;
import dev.feddi.federation.engine.parser.FieldSelectionMapPrinter;
import graphql.language.AstPrinter;
import graphql.language.Field;
import graphql.language.InlineFragment;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.language.SelectionSet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Represents a single execution step in the query plan.
 * Each step corresponds to a request to a specific subgraph.
 *
 * @param id unique identifier for this step
 * @param subgraph the target subgraph for this step
 * @param operation the GraphQL operation to execute in this step
 * @param dependsOn list of step IDs that must complete before this step
 * @param parallelWith list of step IDs that can be executed in parallel with this step
 * @param requirements variable mappings for data needed from previous steps (argument name to FieldSelectionMap)
 * @param repeatedExecution true if this step may need to be executed multiple times (once per entity from parent step)
 * @param artificialFieldPaths dot-notation paths of fields added for internal purposes (not requested by client)
 * @param requestedFieldPaths dot-notation paths of fields explicitly requested by the client
 * @param keyRequirementNames requirement argument names that come from {@code @is} lookup keys.
 *        Null/missing keys skip the subgraph call.
 * @param nonNullRequireArguments non-null {@code @require} arguments keyed by argument name, with
 *        owning field metadata. Null resolved values skip the subgraph call (Cases 2/3); Case 3
 *        also emits {@code REQUIRED_ARGUMENT_NULL} when the field return type is non-null.
 */
public record ExecutionStep(
    int id,
    String subgraph,
    OperationDefinition operation,
    List<Integer> dependsOn,
    List<Integer> parallelWith,
    Map<String, SelectedValue> requirements,
    boolean repeatedExecution,
    Set<String> artificialFieldPaths,
    Set<String> requestedFieldPaths,
    Set<String> keyRequirementNames,
    Map<String, RequireArgumentSkipInfo> nonNullRequireArguments
) {

    public ExecutionStep {
        if (id < 1) {
            throw new IllegalArgumentException("id must be positive");
        }
        if (subgraph == null || subgraph.isBlank()) {
            throw new IllegalArgumentException("subgraph cannot be null or blank");
        }
        if (operation == null) {
            throw new IllegalArgumentException("operation cannot be null");
        }
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        parallelWith = parallelWith == null ? List.of() : List.copyOf(parallelWith);
        requirements = requirements == null ? Map.of() : Map.copyOf(requirements);
        artificialFieldPaths = artificialFieldPaths == null ? Set.of() : Set.copyOf(artificialFieldPaths);
        requestedFieldPaths = requestedFieldPaths == null ? Set.of() : Set.copyOf(requestedFieldPaths);
        keyRequirementNames = keyRequirementNames == null
            ? Set.copyOf(requirements.keySet())
            : Set.copyOf(keyRequirementNames);
        nonNullRequireArguments = nonNullRequireArguments == null
            ? Map.of()
            : Map.copyOf(nonNullRequireArguments);
    }

    /**
     * Creates a root step with no dependencies.
     */
    public static ExecutionStep root(int id, String subgraph, OperationDefinition operation) {
        return new ExecutionStep(id, subgraph, operation, List.of(), List.of(), Map.of(), false,
            Set.of(), Set.of(), Set.of(), Map.of());
    }

    /**
     * Creates a dependent step.
     * All requirements are treated as key requirements (legacy default).
     */
    public static ExecutionStep dependent(
        int id,
        String subgraph,
        OperationDefinition operation,
        List<Integer> dependsOn,
        Map<String, SelectedValue> requirements,
        boolean repeatedExecution
    ) {
        Set<String> keyNames = requirements == null ? Set.of() : Set.copyOf(requirements.keySet());
        return new ExecutionStep(id, subgraph, operation, dependsOn, List.of(), requirements,
            repeatedExecution, Set.of(), Set.of(), keyNames, Map.of());
    }

    /**
     * Creates a dependent step with parallel execution information.
     * All requirements are treated as key requirements (legacy default).
     */
    public static ExecutionStep dependent(
        int id,
        String subgraph,
        OperationDefinition operation,
        List<Integer> dependsOn,
        List<Integer> parallelWith,
        Map<String, SelectedValue> requirements,
        boolean repeatedExecution
    ) {
        Set<String> keyNames = requirements == null ? Set.of() : Set.copyOf(requirements.keySet());
        return new ExecutionStep(id, subgraph, operation, dependsOn, parallelWith, requirements,
            repeatedExecution, Set.of(), Set.of(), keyNames, Map.of());
    }

    /**
     * Checks if this step has any artificial fields that should be filtered.
     */
    public boolean hasArtificialFields() {
        return !artificialFieldPaths.isEmpty();
    }

    /**
     * Returns a flattened list of all field names in this step.
     * Useful for debugging and simple comparisons.
     */
    public List<String> flattenedFields() {
        List<String> result = new ArrayList<>();
        if (operation.getSelectionSet() != null) {
            flattenSelectionSet(operation.getSelectionSet(), result);
        }
        return result;
    }

    private void flattenSelectionSet(SelectionSet selectionSet, List<String> result) {
        for (Selection<?> selection : selectionSet.getSelections()) {
            if (selection instanceof Field field) {
                result.add(field.getName());
                if (field.getSelectionSet() != null) {
                    flattenSelectionSet(field.getSelectionSet(), result);
                }
            } else if (selection instanceof InlineFragment inlineFragment) {
                if (inlineFragment.getSelectionSet() != null) {
                    flattenSelectionSet(inlineFragment.getSelectionSet(), result);
                }
            }
        }
    }

    /**
     * Generates a GraphQL query string from this step's operation.
     * Uses AstPrinter to properly render the operation including variable definitions.
     */
    public String toGraphQL() {
        return AstPrinter.printAst(operation);
    }

    /**
     * Checks if this is a root step (no dependencies).
     */
    public boolean isRoot() {
        return dependsOn.isEmpty();
    }

    /**
     * Checks if this step has requirements from previous steps.
     */
    public boolean hasRequirements() {
        return !requirements.isEmpty();
    }

    /**
     * Checks if this step can run in parallel with other steps.
     */
    public boolean hasParallelSteps() {
        return !parallelWith.isEmpty();
    }

    /**
     * Returns only the requirements that come from {@code @is} lookup keys.
     */
    public Map<String, SelectedValue> keyRequirements() {
        if (keyRequirementNames.isEmpty() || requirements.isEmpty()) {
            return Map.of();
        }
        return requirements.entrySet().stream()
            .filter(e -> keyRequirementNames.contains(e.getKey()))
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                Map.Entry::getValue,
                (a, b) -> a,
                LinkedHashMap::new
            ));
    }

    @Override
    public String toString() {
        var sb = new StringBuilder();
        sb.append(String.format("Step %d [%s]: %s", id, subgraph, flattenedFields()));
        if (!dependsOn.isEmpty()) {
            sb.append(String.format(" (depends on: %s)", dependsOn));
        }
        if (!parallelWith.isEmpty()) {
            sb.append(String.format(" (parallel with: %s)", parallelWith));
        }
        if (!requirements.isEmpty()) {
            Map<String, String> printableReqs = requirements.entrySet().stream()
                .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    e -> FieldSelectionMapPrinter.print(e.getValue())
                ));
            sb.append(String.format(" (requires: %s)", printableReqs));
        }
        if (repeatedExecution) {
            sb.append(" [repeated]");
        }
        return sb.toString();
    }
}
