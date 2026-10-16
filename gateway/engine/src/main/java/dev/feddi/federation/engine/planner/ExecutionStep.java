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
 * @param entityPath response keys locating lookup targets from the response root; required by the
 *                   executor for repeated steps, null only for root steps and expected plans in tests
 * @param requestedFieldPaths dot-notation paths of fields explicitly requested by the client
 * @param entityTypes concrete types an entity at {@code entityPath} must have for this step to apply
 *                    (by its {@code __typename}); empty means any. A lookup for {@code Book} below an
 *                    abstract field must not run for the list's {@code Magazine} items.
 * @param entityPathTypes concrete types the objects along {@code entityPath} must have, by the number of path
 *                        segments that lead to them (1 is the object at the first response key); objects of
 *                        other types are skipped. A lookup below {@code ... on UserReview { product }} must not
 *                        run for an {@code AnonymousReview}'s product.
 * @param possibleTypes the possible object types of the interfaces and unions used as type conditions in
 *                      {@code requirements}, so that {@code <Bar>.bar} matches a {@code Qux} implementing Bar
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
    List<String> entityPath,
    Set<String> entityTypes,
    Map<Integer, Set<String>> entityPathTypes,
    Map<String, Set<String>> possibleTypes
) {

    /**
     * Constructor without an entity path, for root steps and for describing expected plans in tests.
     * The executor rejects repeated steps built this way.
     */
    public ExecutionStep(int id, String subgraph, OperationDefinition operation,
                         List<Integer> dependsOn, List<Integer> parallelWith,
                         Map<String, SelectedValue> requirements, boolean repeatedExecution,
                         Set<String> artificialFieldPaths, Set<String> requestedFieldPaths) {
        this(id, subgraph, operation, dependsOn, parallelWith, requirements, repeatedExecution,
            artificialFieldPaths, requestedFieldPaths, null, Set.of(), Map.of(), Map.of());
    }

    /**
     * Constructor without entity types: the step applies to every entity at its entity path.
     */
    public ExecutionStep(int id, String subgraph, OperationDefinition operation,
                         List<Integer> dependsOn, List<Integer> parallelWith,
                         Map<String, SelectedValue> requirements, boolean repeatedExecution,
                         Set<String> artificialFieldPaths, Set<String> requestedFieldPaths,
                         List<String> entityPath) {
        this(id, subgraph, operation, dependsOn, parallelWith, requirements, repeatedExecution,
            artificialFieldPaths, requestedFieldPaths, entityPath, Set.of(), Map.of(), Map.of());
    }

    public ExecutionStep {
        // Interned like the field names of parsed responses, so lookups along the path match by reference
        entityPath = entityPath == null ? null : entityPath.stream().map(String::intern).toList();
        entityTypes = entityTypes == null ? Set.of() : Set.copyOf(entityTypes);
        entityPathTypes = entityPathTypes == null ? Map.of() : Map.copyOf(entityPathTypes);
        possibleTypes = possibleTypes == null ? Map.of() : Map.copyOf(possibleTypes);
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
    }

    /** This step, depending on other steps. */
    public ExecutionStep withDependsOn(List<Integer> dependsOn) {
        return new ExecutionStep(id, subgraph, operation, dependsOn, parallelWith, requirements, repeatedExecution,
            artificialFieldPaths, requestedFieldPaths, entityPath, entityTypes, entityPathTypes, possibleTypes);
    }

    /** This step, running in parallel with other steps. */
    public ExecutionStep withParallelWith(List<Integer> parallelWith) {
        return new ExecutionStep(id, subgraph, operation, dependsOn, parallelWith, requirements, repeatedExecution,
            artificialFieldPaths, requestedFieldPaths, entityPath, entityTypes, entityPathTypes, possibleTypes);
    }

    /** This step, for entities along its entity path of other types. */
    public ExecutionStep withEntityPathTypes(Map<Integer, Set<String>> entityPathTypes) {
        return new ExecutionStep(id, subgraph, operation, dependsOn, parallelWith, requirements, repeatedExecution,
            artificialFieldPaths, requestedFieldPaths, entityPath, entityTypes, entityPathTypes, possibleTypes);
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
                // Also flatten fields inside inline fragments
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
