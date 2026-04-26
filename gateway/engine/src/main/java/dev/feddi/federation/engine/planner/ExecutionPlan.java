package dev.feddi.federation.engine.planner;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Represents a complete execution plan for a query.
 * Contains an ordered list of execution steps.
 *
 * @param steps the list of execution steps in dependency order
 */
public record ExecutionPlan(List<ExecutionStep> steps) {
    
    public ExecutionPlan {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
    
    /**
     * Creates an ExecutionPlan with the given steps.
     */
    public static ExecutionPlan of(ExecutionStep... steps) {
        return new ExecutionPlan(List.of(steps));
    }
    
    /**
     * Creates an ExecutionPlan with the given steps.
     */
    public static ExecutionPlan of(List<ExecutionStep> steps) {
        return new ExecutionPlan(steps);
    }
    
    /**
     * Creates an empty execution plan.
     */
    public static ExecutionPlan empty() {
        return new ExecutionPlan(List.of());
    }
    
    /**
     * Returns the number of steps in this plan.
     */
    public int stepCount() {
        return steps.size();
    }
    
    /**
     * Checks if this plan is empty.
     */
    public boolean isEmpty() {
        return steps.isEmpty();
    }
    
    /**
     * Gets the root steps (steps with no dependencies).
     */
    public List<ExecutionStep> rootSteps() {
        return steps.stream()
            .filter(ExecutionStep::isRoot)
            .toList();
    }
    
    /**
     * Gets all unique subgraphs involved in this plan.
     */
    public List<String> involvedSubgraphs() {
        return steps.stream()
            .map(ExecutionStep::subgraph)
            .distinct()
            .toList();
    }
    
    /**
     * Calculates the total cost based on number of steps (simple metric).
     */
    public int totalCost() {
        return steps.size();
    }

    /**
     * Aggregates all artificial field paths from all steps.
     * These are fields that were added for internal purposes (lookups, @require)
     * and should be filtered from the final response.
     */
    public Set<String> allArtificialFieldPaths() {
        Set<String> paths = new HashSet<>();
        for (ExecutionStep step : steps) {
            paths.addAll(step.artificialFieldPaths());
        }
        return paths;
    }

    /**
     * Aggregates all requested field paths from all steps.
     * These are fields that were explicitly requested by the client.
     */
    public Set<String> allRequestedFieldPaths() {
        Set<String> paths = new HashSet<>();
        for (ExecutionStep step : steps) {
            paths.addAll(step.requestedFieldPaths());
        }
        return paths;
    }

    /**
     * Returns paths that should be filtered from the final response.
     * A field should be filtered if it's artificial AND NOT requested.
     * This handles cases where the same field path is artificial in one step
     * but explicitly requested in another step.
     */
    public Set<String> pathsToFilter() {
        Set<String> artificial = allArtificialFieldPaths();
        Set<String> requested = allRequestedFieldPaths();
        Set<String> toFilter = new HashSet<>(artificial);
        toFilter.removeAll(requested);
        return toFilter;
    }

    /**
     * Checks if any step has artificial fields that need filtering.
     */
    public boolean hasArtificialFields() {
        return steps.stream().anyMatch(ExecutionStep::hasArtificialFields);
    }
    
    @Override
    public String toString() {
        if (isEmpty()) {
            return "ExecutionPlan(empty)";
        }
        return "ExecutionPlan:\n" + steps.stream()
            .map(s -> "  " + s.toString())
            .collect(Collectors.joining("\n"));
    }
}
