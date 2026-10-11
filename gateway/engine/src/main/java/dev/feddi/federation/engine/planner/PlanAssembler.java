package dev.feddi.federation.engine.planner;

import dev.feddi.federation.engine.graph.Graph;
import dev.feddi.federation.engine.parser.FieldSelectionMap.Alternative;
import dev.feddi.federation.engine.parser.FieldSelectionMap.ListSelection;
import dev.feddi.federation.engine.parser.FieldSelectionMap.ObjectSelection;
import dev.feddi.federation.engine.parser.FieldSelectionMap.Path;
import dev.feddi.federation.engine.parser.FieldSelectionMap.PathSegment;
import dev.feddi.federation.engine.parser.FieldSelectionMap.SelectedValue;
import graphql.language.AstPrinter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Turns the subgraph plans of an operation into the steps of its execution plan: dependencies, whether a step runs
 * per entity, the type conditions along its entity path, and steps merged across type branches.
 */
final class PlanAssembler {
    private final Graph graph;
    private final Collection<SubgraphPlan> plans;
    private final Map<Integer, Set<Integer>> planDependencies;

    PlanAssembler(Graph graph, Collection<SubgraphPlan> plans, Map<Integer, Set<Integer>> planDependencies) {
        this.graph = graph;
        this.plans = plans;
        this.planDependencies = planDependencies;
    }

    /**
     * Builds the final execution plan.
     */
    ExecutionPlan assemble() {
        List<ExecutionStep> steps = new ArrayList<>();

        // Create execution steps (without parallelWith initially)
        for (SubgraphPlan plan : plans) {
            // Get dependencies for this plan (by plan ID, not subgraph name)
            Set<Integer> deps = planDependencies.getOrDefault(plan.id, Set.of());

            List<Integer> dependsOn = deps.stream()
                .sorted()
                .toList();

            // Extract just the SelectedValue from requirements for ExecutionStep
            Map<String, SelectedValue> stepRequirements = new LinkedHashMap<>();
            for (var entry : plan.requirements.entrySet()) {
                stepRequirements.put(entry.getKey(), entry.getValue().selection());
            }

            // A lookup step runs once per entity; a root step with requirements (of a root field) runs once
            boolean repeatedExecution = plan.lookupEdge != null;

            // Collect artificial and requested field paths for this step
            Set<String> artificialPaths = plan.collectArtificialFieldPaths();
            Set<String> requestedPaths = plan.collectRequestedFieldPaths();

            ExecutionStep step = new ExecutionStep(
                plan.id,
                plan.subgraph,
                plan.buildOperation(),
                dependsOn,
                List.of(),  // parallelWith - computed below
                stepRequirements,
                repeatedExecution,
                artificialPaths,
                requestedPaths,
                plan.lookupEntryPath,
                graph.concreteTypes(plan.lookupTargetType()),
                concreteTypesByDepth(plan.lookupPathTypes),
                possibleTypesOfConditions(stepRequirements.values())
            );

            steps.add(step);
        }

        steps = mergeLookupsOfTypeBranches(steps);

        // Sort steps by dependencies (topological order)
        steps.sort(Comparator.comparingInt(ExecutionStep::id));

        // Compute parallelWith: steps with identical dependsOn can run in parallel
        List<ExecutionStep> stepsWithParallel = computeParallelWith(steps);

        return ExecutionPlan.of(stepsWithParallel);
    }

    /**
     * Merges lookup steps that only differ in the type condition at one depth of their entity path: the same
     * operation with the same requirements below {@code ... on Book} and below {@code ... on Magazine} becomes
     * one step for both types (one request instead of two). Steps that depended on a merged step depend on
     * the remaining one.
     */
    private List<ExecutionStep> mergeLookupsOfTypeBranches(List<ExecutionStep> steps) {
        List<ExecutionStep> result = new ArrayList<>(steps);
        Map<Integer, Integer> replacedBy = new HashMap<>();
        boolean merged = true;
        while (merged) {
            merged = false;
            search:
            for (int i = 0; i < result.size(); i++) {
                for (int j = i + 1; j < result.size(); j++) {
                    ExecutionStep a = result.get(i);
                    ExecutionStep b = result.get(j);
                    Map<Integer, Set<String>> pathTypes = mergedPathTypes(a, b);
                    if (pathTypes == null) {
                        continue;
                    }
                    Set<Integer> dependsOn = new TreeSet<>(a.dependsOn());
                    dependsOn.addAll(b.dependsOn());
                    dependsOn.remove(a.id());
                    dependsOn.remove(b.id());
                    result.set(i, new ExecutionStep(a.id(), a.subgraph(), a.operation(), List.copyOf(dependsOn),
                        List.of(), a.requirements(), a.repeatedExecution(), a.artificialFieldPaths(),
                        a.requestedFieldPaths(), a.entityPath(), a.entityTypes(), pathTypes, a.possibleTypes()));
                    result.remove(j);
                    replacedBy.put(b.id(), a.id());
                    merged = true;
                    break search;
                }
            }
        }
        if (replacedBy.isEmpty()) {
            return result;
        }
        List<ExecutionStep> remapped = new ArrayList<>();
        for (ExecutionStep step : result) {
            Set<Integer> dependsOn = new TreeSet<>();
            for (int dependency : step.dependsOn()) {
                int target = dependency;
                while (replacedBy.containsKey(target)) {
                    target = replacedBy.get(target);
                }
                if (target != step.id()) {
                    dependsOn.add(target);
                }
            }
            remapped.add(new ExecutionStep(step.id(), step.subgraph(), step.operation(), List.copyOf(dependsOn),
                List.of(), step.requirements(), step.repeatedExecution(), step.artificialFieldPaths(),
                step.requestedFieldPaths(), step.entityPath(), step.entityTypes(), step.entityPathTypes(),
                step.possibleTypes()));
        }
        return remapped;
    }

    /** The entity path types of a step for both a and b, or null if they cannot be merged. */
    private static Map<Integer, Set<String>> mergedPathTypes(ExecutionStep a, ExecutionStep b) {
        if (!a.repeatedExecution() || !b.repeatedExecution() || !a.subgraph().equals(b.subgraph())
            || !Objects.equals(a.entityPath(), b.entityPath()) || !a.entityTypes().equals(b.entityTypes())
            || !a.requirements().equals(b.requirements()) || !a.possibleTypes().equals(b.possibleTypes())
            || !a.entityPathTypes().keySet().equals(b.entityPathTypes().keySet())
            || !AstPrinter.printAstCompact(a.operation()).equals(AstPrinter.printAstCompact(b.operation()))) {
            return null;
        }
        Map<Integer, Set<String>> merged = new HashMap<>(a.entityPathTypes());
        int differing = 0;
        for (var entry : b.entityPathTypes().entrySet()) {
            if (!entry.getValue().equals(merged.get(entry.getKey()))) {
                differing++;
                Set<String> union = new HashSet<>(merged.get(entry.getKey()));
                union.addAll(entry.getValue());
                merged.put(entry.getKey(), union);
            }
        }
        // Types at two depths cannot be combined: their union would also match mixed branches
        return differing == 1 ? merged : null;
    }

    /** The possible types of the abstract types that the requirements use as type conditions. */
    private Map<String, Set<String>> possibleTypesOfConditions(Iterable<SelectedValue> requirements) {
        Set<String> conditions = new HashSet<>();
        for (SelectedValue requirement : requirements) {
            collectTypeConditions(requirement, conditions);
        }
        Map<String, Set<String>> result = new HashMap<>();
        for (String condition : conditions) {
            Set<String> types = graph.concreteTypes(condition);
            if (!types.equals(Set.of(condition))) {
                result.put(condition, types);
            }
        }
        return result;
    }

    private static void collectTypeConditions(SelectedValue value, Set<String> conditions) {
        for (Alternative alternative : value.alternatives()) {
            switch (alternative) {
                case Path path -> collectTypeConditions(path, conditions);
                case ObjectSelection object -> {
                    if (object.pathPrefix() != null) {
                        collectTypeConditions(object.pathPrefix(), conditions);
                    }
                    object.fields().forEach(field -> collectTypeConditions(field.value(), conditions));
                }
                case ListSelection list -> {
                    if (list.pathPrefix() != null) {
                        collectTypeConditions(list.pathPrefix(), conditions);
                    }
                    collectTypeConditions(list.elementValue(), conditions);
                }
            }
        }
    }

    private static void collectTypeConditions(Path path, Set<String> conditions) {
        if (path.hasInitialTypeCondition()) {
            conditions.add(path.initialTypeCondition());
        }
        for (PathSegment segment : path.segments()) {
            if (segment.hasTypeCondition()) {
                conditions.add(segment.typeCondition());
            }
        }
    }

    private Map<Integer, Set<String>> concreteTypesByDepth(Map<Integer, String> typesByDepth) {
        Map<Integer, Set<String>> result = new HashMap<>();
        typesByDepth.forEach((depth, type) -> result.put(depth, graph.concreteTypes(type)));
        return result;
    }

    /**
     * Computes the parallelWith field for each step.
     * Steps with identical dependsOn lists can be executed in parallel.
     */
    private List<ExecutionStep> computeParallelWith(List<ExecutionStep> steps) {
        // Group steps by their dependsOn list
        Map<List<Integer>, List<ExecutionStep>> byDependencies = steps.stream()
            .collect(Collectors.groupingBy(ExecutionStep::dependsOn));

        // Rebuild steps with parallelWith computed
        List<ExecutionStep> result = new ArrayList<>();
        for (ExecutionStep step : steps) {
            List<ExecutionStep> sameDeps = byDependencies.get(step.dependsOn());

            // Find other steps with same dependencies (exclude self)
            List<Integer> parallelWith = sameDeps.stream()
                .filter(s -> s.id() != step.id())
                .map(ExecutionStep::id)
                .sorted()
                .toList();

            // Create new step with parallelWith set (preserve field paths)
            result.add(new ExecutionStep(
                step.id(),
                step.subgraph(),
                step.operation(),
                step.dependsOn(),
                parallelWith,
                step.requirements(),
                step.repeatedExecution(),
                step.artificialFieldPaths(),
                step.requestedFieldPaths(),
                step.entityPath(),
                step.entityTypes(),
                step.entityPathTypes(),
                step.possibleTypes()
            ));
        }

        return result;
    }
}
