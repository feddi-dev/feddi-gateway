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
