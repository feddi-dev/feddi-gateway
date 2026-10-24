package dev.feddi.federation.engine.planner;

import dev.feddi.federation.engine.graph.Edge;
import dev.feddi.federation.engine.graph.FieldMoveEdge;
import dev.feddi.federation.engine.graph.Graph;
import dev.feddi.federation.engine.graph.LookupArgument;
import dev.feddi.federation.engine.graph.LookupMoveEdge;
import dev.feddi.federation.engine.parser.FieldSelectionMap.Path;
import dev.feddi.federation.engine.query.FieldSelection;
import graphql.language.Argument;
import graphql.language.OperationDefinition;
import graphql.language.VariableDefinition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * The plans of an operation being planned: one per subgraph root and per lookup (by entry path and the type
 * conditions along it), their dependencies, and the lookups and key fields that connect them.
 */
final class PlanningContext {

    /**
     * Key for identifying subgraph plans. Each unique combination of subgraph,
     * entering lookup edge, and lookup entry path gets its own plan. This allows
     * multiple plans per subgraph (e.g., root query + lookups, or the same lookup
     * reached from different response branches).
     *
     * @param subgraph the subgraph name
     * @param entryLookup the lookup edge used to enter this subgraph, or null for root queries
     * @param rootGroup for a root plan of a mutation, the group of top-level fields it executes (see
     *                  {@link #startRootGroup}); 0 otherwise
     */
    record SubgraphPlanKey(String subgraph, LookupMoveEdge entryLookup, List<String> entryPath,
                           Map<Integer, String> pathTypes, int rootGroup) {
        SubgraphPlanKey {
            entryPath = entryPath == null ? List.of() : List.copyOf(entryPath);
            pathTypes = pathTypes == null ? Map.of() : Map.copyOf(pathTypes);
        }

        static SubgraphPlanKey forRoot(String subgraph, int rootGroup) {
            return new SubgraphPlanKey(subgraph, null, List.of(), Map.of(), rootGroup);
        }

        static SubgraphPlanKey forLookup(String subgraph, LookupMoveEdge entryLookup, List<String> entryPath,
                                         Map<Integer, String> pathTypes) {
            return new SubgraphPlanKey(subgraph, entryLookup, entryPath, pathTypes, 0);
        }

        boolean isRootEntry() {
            return entryLookup == null;
        }
    }

    /**
     * A lookup at a response path; {@code pathTypes} are the type conditions of the inline fragments along
     * the path (see {@link ExecutionStep#entityPathTypes()}), so that each fragment gets its own lookup.
     */
    record LookupPlanKey(LookupMoveEdge lookupEdge, List<String> entryPath, Map<Integer, String> pathTypes) {
        LookupPlanKey {
            entryPath = entryPath == null ? List.of() : List.copyOf(entryPath);
            pathTypes = pathTypes == null ? Map.of() : Map.copyOf(pathTypes);
        }
    }

    // Plans keyed by (subgraph, entryLookup, entryPath) to allow multiple plans per subgraph
    final Map<SubgraphPlanKey, SubgraphPlan> subgraphPlans = new LinkedHashMap<>();

    // Dependencies between plans, keyed by plan ID
    final Map<Integer, Set<Integer>> planDependencies = new HashMap<>();

    private final AtomicInteger stepIdGenerator = new AtomicInteger(0);

    // Track which lookup entries have had their lookup arguments added to avoid duplicates
    private final Set<LookupPlanKey> processedLookupEdges = new HashSet<>();

    // Arguments of the fields the client selects, by response path and response key
    final Map<List<String>, Map<String, List<FieldPathSelector.ClientField>>> clientFields = new HashMap<>();

    // Map from lookup edge plus response entry path to the plan that was created for it
    private final Map<LookupPlanKey, SubgraphPlan> lookupEdgeToPlan = new HashMap<>();

    // Query-level variable definitions for pass-through to subgraph operations
    private final List<VariableDefinition> queryVariableDefinitions;

    // The operation type (QUERY or MUTATION) for building subgraph operations
    private final OperationDefinition.Operation operationType;

    // Graph reference for checking field resolution
    private final Graph graph;

    // Type conditions of the inline fragments around the selection being planned, by the length of the
    // response path to the object they apply to
    private final Map<Integer, String> branchTypes = new HashMap<>();

    // Mutations: the current group of top-level fields, and the group each plan was created for
    private int rootGroup;
    private final Map<Integer, Integer> planGroups = new HashMap<>();

    private final RequirementPlanner requirements;
    private final FieldPathSelector fieldPaths;

    PlanningContext(List<VariableDefinition> queryVariableDefinitions, OperationDefinition.Operation operationType, Graph graph) {
        this.queryVariableDefinitions = queryVariableDefinitions != null
            ? queryVariableDefinitions : List.of();
        this.operationType = operationType;
        this.graph = graph;
        this.requirements = new RequirementPlanner(this, graph);
        this.fieldPaths = new FieldPathSelector(graph, clientFields);
    }

    /** The execution plan of the plans built so far. */
    /** The type each lookup plan looks up, by plan (and step) id. */
    Map<Integer, String> lookupTargetTypes() {
        Map<Integer, String> types = new HashMap<>();
        for (SubgraphPlan plan : subgraphPlans.values()) {
            if (plan.lookupTargetType() != null) {
                types.put(plan.id, plan.lookupTargetType());
            }
        }
        return types;
    }

    ExecutionPlan buildPlan() {
        // A mutation's groups of top-level fields execute in order: each group's root plans wait for every plan
        // of the group before
        for (SubgraphPlan plan : subgraphPlans.values()) {
            int group = planGroups.get(plan.id);
            if (plan.lookupEdge == null && group > 0) {
                for (SubgraphPlan previous : subgraphPlans.values()) {
                    if (planGroups.get(previous.id) == group - 1) {
                        addPlanDependency(plan, previous);
                    }
                }
            }
        }
        return new PlanAssembler(graph, subgraphPlans.values(), planDependencies).assemble();
    }

    /** Enters an inline fragment on the object at {@code depth}; returns the condition it replaces. */
    String enterTypeCondition(int depth, String typeCondition) {
        return branchTypes.put(depth, typeCondition);
    }

    void exitTypeCondition(int depth, String previous) {
        if (previous == null) {
            branchTypes.remove(depth);
        } else {
            branchTypes.put(depth, previous);
        }
    }

    /** The current type conditions on the objects above the end of {@code entryPath}. */
    private Map<Integer, String> pathTypes(List<String> entryPath) {
        Map<Integer, String> types = new HashMap<>();
        branchTypes.forEach((depth, type) -> {
            if (depth > 0 && depth < entryPath.size()) {
                types.put(depth, type);
            }
        });
        return types;
    }

    LookupPlanKey lookupKey(LookupMoveEdge lookupEdge, List<String> entryPath) {
        return new LookupPlanKey(lookupEdge, entryPath, pathTypes(entryPath == null ? List.of() : entryPath));
    }

    /**
     * Starts the next group of a mutation's top-level fields. GraphQL executes them one after another: each group
     * (consecutive fields of one subgraph) gets its own root plan, which waits for all plans of the group before
     * it, including the lookups below its fields.
     */
    void startRootGroup() {
        rootGroup++;
    }

    /** The current group of a mutation's top-level fields (see {@link #startRootGroup}); 0 for queries. */
    int rootGroup() {
        return rootGroup;
    }

    /**
     * The group of a query's root fields with @require arguments: they get their own root plan per subgraph, which
     * waits for the root plans that select the required fields (two subgraphs whose root fields require each
     * other's root fields would otherwise wait for each other).
     */
    static final int REQUIRING_ROOT_FIELDS = -1;

    /** Sets the group root plans are created for; returns the previous one. */
    int setRootGroup(int group) {
        int previous = rootGroup;
        rootGroup = group;
        return previous;
    }

    /**
     * Gets or creates a plan for a root query (no lookup entry), of the current group of top-level fields.
     */
    SubgraphPlan getOrCreateRootPlan(String subgraph) {
        SubgraphPlanKey key = SubgraphPlanKey.forRoot(subgraph, rootGroup);
        return subgraphPlans.computeIfAbsent(key, k -> newPlan(subgraph));
    }

    private SubgraphPlan newPlan(String subgraph) {
        SubgraphPlan plan = new SubgraphPlan(stepIdGenerator.incrementAndGet(), subgraph, queryVariableDefinitions,
            operationType);
        planGroups.put(plan.id, rootGroup);
        return plan;
    }

    /**
     * Gets or creates a plan for a lookup entry.
     */
    SubgraphPlan getOrCreateLookupPlan(LookupMoveEdge lookupEdge, List<String> parentPath) {
        LookupPlanKey lookupPlanKey = lookupKey(lookupEdge, parentPath);

        // Check if we already have a plan for this lookup edge at this response path
        SubgraphPlan existingPlan = lookupEdgeToPlan.get(lookupPlanKey);
        if (existingPlan != null) {
            return existingPlan;
        }

        String subgraph = lookupEdge.target().subgraph();
        SubgraphPlanKey key = SubgraphPlanKey.forLookup(subgraph, lookupEdge, parentPath, lookupPlanKey.pathTypes());
        SubgraphPlan plan = subgraphPlans.computeIfAbsent(key, k -> {
            SubgraphPlan newPlan = newPlan(subgraph);
            newPlan.setLookupOrigin(lookupEdge, parentPath, lookupPlanKey.pathTypes());
            return newPlan;
        });
        lookupEdgeToPlan.put(lookupPlanKey, plan);
        return plan;
    }

    /**
     * Adds a dependency: targetPlan depends on sourcePlan.
     */
    void addPlanDependency(SubgraphPlan targetPlan, SubgraphPlan sourcePlan) {
        if (targetPlan.id != sourcePlan.id) {
            planDependencies
                .computeIfAbsent(targetPlan.id, k -> new HashSet<>())
                .add(sourcePlan.id);
        }
    }

    /**
     * Whether the {@code lookupIndex}-th lookup on a path to a field enters the field's own object (it is the
     * edge right before the field). Only that lookup carries the field's @require values: requirements are per
     * object, and an earlier lookup into the same type was for another object.
     */
    private static boolean entersFieldObject(OperationPath path, int lookupIndex) {
        List<Edge> edges = path.getEdges();
        int seen = -1;
        for (int i = 0; i < edges.size(); i++) {
            if (edges.get(i) instanceof LookupMoveEdge && ++seen == lookupIndex) {
                return i == edges.size() - 2;
            }
        }
        return false;
    }

    /** A lookup on a path, with the response path where it starts. */
    private record LookupStep(LookupMoveEdge edge, List<String> entryPath) {
    }

    /** The lookups on a path in order, each with its own entry path (in the client's response keys). */
    private static List<LookupStep> lookupSteps(OperationPath path, List<String> parentPath) {
        List<LookupStep> steps = new ArrayList<>();
        int depth = 0;
        for (Edge edge : path.getEdges()) {
            if (edge instanceof LookupMoveEdge lookupEdge) {
                steps.add(new LookupStep(lookupEdge,
                    depth <= parentPath.size() ? List.copyOf(parentPath.subList(0, depth)) : parentPath));
            } else {
                depth++;
            }
        }
        return steps;
    }

    /**
     * Records a field resolution with hierarchical information.
     */
    void recordFieldResolution(OperationPath path, String alias, String fieldName,
                               List<String> parentPath, boolean hasChildren,
                               List<Argument> queryArguments) {
        recordFieldResolution(path, alias, fieldName, parentPath, hasChildren, queryArguments, null);
    }

    /**
     * Records a field resolution; {@code keyFragment}, if not null, is the inline fragment the field is
     * selected in: a lookup that starts at the field's parent selects its key fields there.
     */
    void recordFieldResolution(OperationPath path, String alias, String fieldName,
                               List<String> parentPath, boolean hasChildren,
                               List<Argument> queryArguments, InlineFragmentContext keyFragment) {
        String subgraph = path.currentSubgraph();

        // Find the last lookup edge that enters the current subgraph (if any)
        SubgraphPlan plan = planFor(path, parentPath);

        // Add the field to this subgraph's plan with its hierarchical position and arguments
        plan.addFieldWithAlias(alias, fieldName, parentPath, hasChildren, queryArguments);

        // Process all lookup edges in the path to set up dependencies and key fields
        processLookupEdgesInPath(path, fieldName, parentPath, plan, keyFragment);

        List<Edge> edges = path.getEdges();
        if (!edges.isEmpty() && edges.get(edges.size() - 1) instanceof FieldMoveEdge fieldEdge
            && graph.isRootOperationType(fieldEdge.source().typeName())) {
            requirements.processRootRequirements(plan, fieldEdge.source().typeName(), fieldName);
        }
    }

    /**
     * Processes lookup edges in a path to set up dependencies and key fields.
     */
    private void processLookupEdgesInPath(OperationPath path, String fieldName,
                                           List<String> parentPath, SubgraphPlan targetPlan,
                                           InlineFragmentContext keyFragment) {
        String targetSubgraph = targetPlan.subgraph;

        // Build a chain of lookup edges and their corresponding plans
        // Each lookup's source needs to have key fields added
        List<LookupStep> lookupChain = lookupSteps(path, parentPath);

        // Process each lookup edge in the chain
        for (int i = 0; i < lookupChain.size(); i++) {
            LookupMoveEdge lookupEdge = lookupChain.get(i).edge();
            String sourceSubgraph = lookupEdge.source().subgraph();
            List<String> entryPath = lookupChain.get(i).entryPath();

            // Find the source plan - it's either a previous lookup's target or the root plan
            SubgraphPlan sourcePlan;
            if (i > 0) {
                // Source is the previous lookup's target
                LookupMoveEdge prevLookup = lookupChain.get(i - 1).edge();
                List<String> prevEntryPath = lookupChain.get(i - 1).entryPath();
                sourcePlan = lookupEdgeToPlan.get(lookupKey(prevLookup, prevEntryPath));
                if (sourcePlan == null) {
                    throw new PlanningException("Source plan not found for lookup edge from '" +
                        sourceSubgraph + "' - previous lookup in chain was not properly registered");
                }
            } else {
                // First lookup, source is root plan
                sourcePlan = getOrCreateRootPlan(sourceSubgraph);
            }

            // Get or create the target plan for this lookup
            SubgraphPlan lookupTargetPlan = getOrCreateLookupPlan(lookupEdge, entryPath);

            // Add dependency: lookupTargetPlan depends on sourcePlan
            addPlanDependency(lookupTargetPlan, sourcePlan);

            // Key fields: in the fragment the field is selected in, fetched like a requirement when the source
            // subgraph does not have them, or on the lookup's source type in the source plan
            addLookupKeys(lookupEdge, entryPath, lookupTargetPlan, keyPath -> {
                if (keyFragment != null && keyFragment.subgraph.equals(sourceSubgraph) && entryPath.equals(parentPath)) {
                    return fieldPaths.addKeyPathInFragment(keyFragment, keyPath);
                }
                if (!requirements.keyInSourceSubgraph(sourceSubgraph, lookupEdge.source().typeName(), keyPath)) {
                    RequirementPlanner.RequireFieldResolution resolution = RequirementPlanner.resolved(requirements.resolveRequireField(sourceSubgraph,
                        lookupEdge.source().typeName(), keyPath, entryPath,
                        new HashSet<>(Set.of(lookupEdge.target().subgraph()))), keyPath, lookupEdge.source().typeName());
                    addPlanDependency(lookupTargetPlan, resolution.plan());
                    return resolution.fieldToResponseKey();
                }
                return addKeyPath(sourcePlan, keyPath, entryPath, lookupEdge);
            });

            // Process @require fields for the last lookup in the chain that enters the target subgraph
            if (lookupEdge.target().subgraph().equals(targetSubgraph) && lookupEdge.hasRequirements()
                && entersFieldObject(path, i)) {
                requirements.processRequireFields(lookupEdge, fieldName, parentPath, sourcePlan, lookupTargetPlan);
            }
        }
    }

    /**
     * Records an opaque field resolution (e.g., introspection fields).
     * The entire field selection tree is stored and rendered as-is.
     */
    void recordOpaqueFieldResolution(OperationPath path, FieldSelection selection, List<String> parentPath) {
        String subgraph = path.currentSubgraph();

        // Get or create subgraph plan for introspection (always root plan)
        SubgraphPlan plan = getOrCreateRootPlan(subgraph);

        // Add the opaque field with its full selection tree
        plan.addOpaqueField(selection, parentPath);
    }

    /**
     * The plan a lookup on {@code path} starts from: the plan of the lookup before it on the path that entered
     * its source subgraph, or the source subgraph's root plan. (A search by subgraph and response path cannot
     * tell apart lookups under different type conditions on the same path.)
     */
    SubgraphPlan sourcePlanOf(OperationPath path, int lookupIndex, List<String> parentPath) {
        List<LookupStep> steps = lookupSteps(path, parentPath);
        String sourceSubgraph = steps.get(lookupIndex).edge().source().subgraph();
        LookupStep previous = null;
        for (int i = 0; i < lookupIndex; i++) {
            if (steps.get(i).edge().target().subgraph().equals(sourceSubgraph)) {
                previous = steps.get(i);
            }
        }
        return previous == null ? getOrCreateRootPlan(sourceSubgraph)
            : getOrCreateLookupPlan(previous.edge(), previous.entryPath());
    }

    /** The index of the last lookup on the path that enters {@code subgraph}, or -1. */
    private static int enteringLookupIndex(OperationPath path, List<String> parentPath, String subgraph) {
        List<LookupStep> steps = lookupSteps(path, parentPath);
        for (int i = steps.size() - 1; i >= 0; i--) {
            if (steps.get(i).edge().target().subgraph().equals(subgraph)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The plan that selects at the end of {@code path}: the plan of the last lookup into its subgraph, or the
     * subgraph's root plan.
     */
    SubgraphPlan planFor(OperationPath path, List<String> parentPath) {
        String subgraph = path.currentSubgraph();
        LookupStep entering = enteringLookup(path, parentPath);
        return entering == null ? getOrCreateRootPlan(subgraph)
            : getOrCreateLookupPlan(entering.edge(), entering.entryPath());
    }

    /** The last lookup on the path into its current subgraph, with its entry path; null if there is none. */
    private static LookupStep enteringLookup(OperationPath path, List<String> parentPath) {
        LookupStep entering = null;
        for (LookupStep step : lookupSteps(path, parentPath)) {
            if (step.edge().target().subgraph().equals(path.currentSubgraph())) {
                entering = step;
            }
        }
        return entering;
    }

    /**
     * Passes a lookup's arguments to its step, once per lookup and entry path: each alternative key path is
     * selected by {@code placeKey} (which returns the response keys it used) and the argument becomes a
     * requirement of the lookup step.
     */
    void addLookupKeys(LookupMoveEdge lookupEdge, List<String> entryPath, SubgraphPlan lookupPlan,
                               Function<Path, Map<String, String>> placeKey) {
        if (!processedLookupEdges.add(lookupKey(lookupEdge, entryPath))) {
            return;
        }
        for (LookupArgument argument : lookupEdge.lookupArguments()) {
            Map<String, String> responseKeys = new LinkedHashMap<>();
            for (Path alternative : argument.extractPaths()) {
                responseKeys.putAll(placeKey.apply(alternative));
            }
            lookupPlan.addRequirement(argument.argumentName(), ResponseKeys.createRequirementSelectedValue(argument, responseKeys),
                argument.argumentType());
        }
    }

    /**
     * Selects a lookup's key path in its source plan, on the lookup's source type: below an abstract field (a
     * union or interface) the key goes into a fragment on that type.
     */
    Map<String, String> addKeyPath(SubgraphPlan sourcePlan, Path keyPath, List<String> entryPath,
                                           LookupMoveEdge lookupEdge) {
        return addNestedFieldPathWithSubgraph(sourcePlan, keyPath, entryPath, FieldOrigin.ARTIFICIAL_KEY,
            sourcePlan.subgraph, lookupEdge.source().typeName());
    }

    /**
     * Adds a potentially nested field path to the plan.
     * For example, Path("dimension.size") adds "dimension" with children, then "size" under it.
     * Also handles type-conditioned paths like "&lt;Movie&gt;.imdbCode" by extracting only the field names.
     * For infix type conditions (e.g., "relatedMedia&lt;Movie&gt;.imdbCode"), creates inline fragments
     * so that type-specific fields are properly fetched from interface types.
     *
     * When a field is not found on an interface but exists on implementing types, automatically
     * generates inline fragments for each implementing type that has the field. For example,
     * @require(field: "data.bar") where data returns Foo interface and bar is on Bar implementing type
     * generates: { data { __typename ... on Bar { bar } } }
     *
     * @return a map from field name to response key for each segment in the path
     */
    Map<String, String> addNestedFieldPath(SubgraphPlan plan, Path path, List<String> parentPath, FieldOrigin origin) {
        return addNestedFieldPathWithSubgraph(plan, path, parentPath, origin, plan.subgraph, null);
    }

    /**
     * Adds a potentially nested field path to the plan with subgraph context for type resolution.
     *
     * @param plan the subgraph plan to add fields to
     * @param path the field path to add
     * @param parentPath the parent path in the selection tree
     * @param origin the field origin (KEY, REQUIRE, etc.)
     * @param subgraph the subgraph for type resolution
     * @param startTypeName the type name to start resolution from (null to derive from path)
     * @return a map from field name to response key for each segment in the path
     */
    Map<String, String> addNestedFieldPathWithSubgraph(SubgraphPlan plan, Path path, List<String> parentPath,
                                                                 FieldOrigin origin, String subgraph, String startTypeName) {
        // A path with a type condition (<Book>.isbn) starts on that type
        String startType = path.hasInitialTypeCondition() ? path.initialTypeCondition() : startTypeName;
        Map<String, String> responseKeys =
            fieldPaths.addNestedFieldPathOnly(plan, path, parentPath, origin, subgraph, startType);
        requirements.processRequirementsOfAddedField(plan, path, parentPath);
        return responseKeys;
    }

    /**
     * Gets or creates an inline fragment context for a cross-subgraph lookup.
     * Sets up the lookup origin and creates the inline fragment in the target subgraph.
     */
    InlineFragmentContext getOrCreateInlineFragmentForLookup(OperationPath path, List<String> parentPath,
                                                              String typeCondition) {
        String subgraph = path.currentSubgraph();

        // Find the last lookup edge that enters this subgraph
        LookupStep entering = enteringLookup(path, parentPath);
        LookupMoveEdge enteringLookupEdge = entering == null ? null : entering.edge();
        List<String> enteringEntryPath = entering == null ? parentPath : entering.entryPath();
        SubgraphPlan plan = planFor(path, parentPath);

        // Add requirements for the lookup
        if (enteringLookupEdge != null) {
            // Find or create source plan
            String sourceSubgraph = enteringLookupEdge.source().subgraph();
            SubgraphPlan sourcePlan = sourcePlanOf(path, enteringLookupIndex(path, parentPath, subgraph), parentPath);

            // Track dependency
            addPlanDependency(plan, sourcePlan);

            // Key fields on the lookup's source type (in a fragment where the abstract type lacks them)
            // Inside a fragment, the fragment's type is the more precise start type (the lookup may be from the
            // union or interface itself)
            String keyType = typeCondition != null && enteringEntryPath.equals(parentPath) ? typeCondition
                : enteringLookupEdge.source().typeName();
            addLookupKeys(enteringLookupEdge, enteringEntryPath, plan, keyPath -> addNestedFieldPathWithSubgraph(
                sourcePlan, keyPath, enteringEntryPath, FieldOrigin.ARTIFICIAL_KEY, sourcePlan.subgraph, keyType));
        }

        if (enteringLookupEdge != null && enteringEntryPath.equals(parentPath)
            && enteringLookupEdge.target().typeName().equals(typeCondition)) {
            // The lookup already returns the fragment's type: its fields need no type condition.
            return new InlineFragmentContext(new SelectionNode.Fragment(plan.root), plan, parentPath, typeCondition);
        }

        return inlineFragmentIn(plan, parentPath, typeCondition);
    }

    /** A client fragment at a response path in a plan, as the context its fields are planned into. */
    InlineFragmentContext inlineFragmentIn(SubgraphPlan plan, List<String> parentPath, String typeCondition) {
        return new InlineFragmentContext(plan.getOrCreateInlineFragment(parentPath, typeCondition), plan,
            parentPath, typeCondition != null ? typeCondition : plan.declaredTypeAt(parentPath, graph));
    }

    /**
     * Records a field the client selects in an inline fragment (which already holds the field): sets up the
     * lookups on its path, their keys and the field's @require values.
     */
    void recordFieldResolutionForFragment(OperationPath path, String fieldName, List<String> parentPath) {
        processLookupEdgesInPath(path, fieldName, parentPath, planFor(path, parentPath), null);
    }
}
