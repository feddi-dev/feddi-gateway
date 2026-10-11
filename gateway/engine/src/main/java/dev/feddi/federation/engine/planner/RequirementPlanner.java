package dev.feddi.federation.engine.planner;

import dev.feddi.federation.engine.Constants;
import dev.feddi.federation.engine.graph.Graph;
import dev.feddi.federation.engine.graph.LookupMoveEdge;
import dev.feddi.federation.engine.graph.Node;
import dev.feddi.federation.engine.graph.Requirement;
import dev.feddi.federation.engine.parser.FieldSelectionMap.Path;
import dev.feddi.federation.engine.parser.FieldSelectionMap.PathSegment;
import dev.feddi.federation.engine.parser.FieldSelectionMap.SelectedValue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Resolves the values of @require arguments: selects the required fields where they can be resolved (in the plan
 * that selects the object, through lookups into other subgraphs, below a field, or on another subgraph's root
 * type for a root field) and passes them to the requiring plan as variables.
 */
final class RequirementPlanner {
    private final PlanningContext context;
    private final Graph graph;

    // Track response keys for @require fields by lookup edge
    // Maps LookupPlanKey -> (fieldName -> responseKey)
    private final Map<PlanningContext.LookupPlanKey, Map<String, String>> requireFieldResponseKeys = new HashMap<>();

    // Plans whose @require fields are being resolved (innermost first): plans waiting for them are no source
    private final Deque<SubgraphPlan> requirementTargets = new ArrayDeque<>();

    // Fields added by the planner whose own @require arguments were processed ("planId|field")
    private final Set<String> requirementsOfAddedFields = new HashSet<>();

    RequirementPlanner(PlanningContext context, Graph graph) {
        this.context = context;
        this.graph = graph;
    }

    /**
     * Resolves the @require arguments of a root field from the root types of the other subgraphs: the plan
     * that selects the field waits for the plans that select the required fields, and reads them from the
     * response root ({@code search(filter: $filter)} after {@code { category }}).
     */
    void processRootRequirements(SubgraphPlan plan, String rootType, String fieldName) {
        if (!Constants.QUERY.equals(rootType) && !graph.requirementsOf(rootType, plan.subgraph, fieldName).isEmpty()) {
            // Resolving them would select another subgraph's mutation field, i.e. run another mutation
            throw new PlanningException("@require on mutation field '" + fieldName + "' in '" + plan.subgraph
                + "' is not supported");
        }
        for (Requirement requirement : graph.requirementsOf(rootType, plan.subgraph, fieldName)) {
            Map<String, String> responseKeys = new HashMap<>();
            for (Path path : requirement.extractPaths()) {
                RequireFieldResolution resolution = resolved(resolveRootRequirement(rootType, path, plan.subgraph),
                    path, rootType);
                responseKeys.putAll(resolution.fieldToResponseKey());
                if (dependsOn(resolution.plan(), plan)) {
                    throw new PlanningException("The requirements of root field '" + fieldName + "' in '"
                        + plan.subgraph + "' depend on that subgraph's own root fields");
                }
                context.addPlanDependency(plan, resolution.plan());
            }
            String variable = plan.addRequirementVariable(requirement.argumentName(),
                ResponseKeys.transformSelectionWithResponseKeys(requirement.selection(), responseKeys), requirement.argumentType());
            plan.addFieldArgument(List.of(fieldName), requirement.argumentName(), variable);
        }
    }

    /**
     * Selects a root field's required path in another subgraph: in the root plan of one whose root type has the
     * whole path, or else through lookups from one that has its first field.
     */
    private RequireFieldResolution resolveRootRequirement(String rootType, Path path, String requiringSubgraph) {
        List<String> candidates = graph.subgraphs().stream()
            .filter(subgraph -> !subgraph.equals(requiringSubgraph) && !subgraph.startsWith("$")
                && graph.containsNode(new Node(rootType, subgraph)))
            .sorted()
            .toList();
        for (String subgraph : candidates) {
            if (canResolveFieldPathInSubgraph(subgraph, rootType, path)) {
                SubgraphPlan rootPlan = context.getOrCreateRootPlan(subgraph);
                return new RequireFieldResolution(rootPlan, subgraph, context.addNestedFieldPathWithSubgraph(rootPlan, path,
                    List.of(), FieldOrigin.ARTIFICIAL_REQUIRE, subgraph, rootType));
            }
        }
        String firstField = path.segments().get(0).fieldName();
        for (String subgraph : candidates) {
            if (graph.findFieldEdge(new Node(rootType, subgraph), firstField).isPresent()) {
                RequireFieldResolution resolution = resolveRequireField(subgraph, rootType, path, List.of(),
                    new HashSet<>(Set.of(requiringSubgraph)), () -> context.getOrCreateRootPlan(subgraph));
                if (resolution != null) {
                    return resolution;
                }
            }
        }
        return null;
    }

    /** Whether {@code plan} waits for {@code other}, directly or through other plans. */
    private boolean dependsOn(SubgraphPlan plan, SubgraphPlan other) {
        Deque<Integer> pending = new ArrayDeque<>(List.of(plan.id));
        Set<Integer> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            int id = pending.pop();
            if (id == other.id) {
                return true;
            }
            if (seen.add(id)) {
                pending.addAll(context.planDependencies.getOrDefault(id, Set.of()));
            }
        }
        return false;
    }

    /**
     * Resolves the @require arguments of a field selected in {@code targetPlan}, entered through
     * {@code lookupEdge} from {@code sourcePlan}; never in the target subgraph itself.
     */
    void processRequireFields(LookupMoveEdge lookupEdge, String fieldName,
                              List<String> parentPath, SubgraphPlan sourcePlan,
                              SubgraphPlan targetPlan) {
        String sourceSubgraph = lookupEdge.source().subgraph();
        String sourceTypeName = lookupEdge.source().typeName();

        Set<String> visitedForRequire = new HashSet<>(Set.of(targetPlan.subgraph));
        requirementTargets.push(targetPlan);
        try {
            processRequireFieldsFor(lookupEdge, fieldName, parentPath, sourcePlan, targetPlan, sourceSubgraph,
                sourceTypeName, visitedForRequire);
        } finally {
            requirementTargets.pop();
        }
    }

    private void processRequireFieldsFor(LookupMoveEdge lookupEdge, String fieldName, List<String> parentPath,
                                         SubgraphPlan sourcePlan, SubgraphPlan targetPlan, String sourceSubgraph,
                                         String sourceTypeName, Set<String> visitedForRequire) {
        // Get or create the response keys map for this lookup edge
        List<String> entryPath = targetPlan.lookupEntryPath != null ? targetPlan.lookupEntryPath : parentPath;
        Map<String, String> reqResponseKeys = requireFieldResponseKeys.computeIfAbsent(
            context.lookupKey(lookupEdge, entryPath), k -> new HashMap<>());

        Set<Path> resolvedPaths = new HashSet<>();
        for (var req : lookupEdge.requires()) {
            // Only resolve requirements for the current field being processed
            if (req.fieldName() != null && !req.fieldName().equals(fieldName)) {
                continue;
            }

            for (Path fieldPath : req.extractPaths()) {
                // Skip if already resolved
                if (resolvedPaths.contains(fieldPath)) {
                    continue;
                }
                resolvedPaths.add(fieldPath);

                // Use recursive resolution to find where to get this field
                // Pass empty parentPath for lookup sources since they start fresh
                List<String> reqParentPath = parentPath; // adjustPath handles lookup prefix stripping
                RequireFieldResolution resolution = resolved(resolveRequireField(
                    sourceSubgraph, sourceTypeName, fieldPath, reqParentPath, visitedForRequire), fieldPath, sourceTypeName);

                if (resolution != null) {
                    reqResponseKeys.putAll(resolution.fieldToResponseKey());

                    // If resolved from a different plan, add dependency
                    if (resolution.plan().id != sourcePlan.id) {
                        context.addPlanDependency(targetPlan, resolution.plan());
                    }
                }
            }

            // Add @require field arguments to the target plan
            if (req.fieldName() == null || req.fieldName().equals(fieldName)) {
                List<String> adjustedFieldPath = new ArrayList<>();
                if (targetPlan.lookupEntryPath != null && parentPath.size() >= targetPlan.lookupEntryPath.size()) {
                    adjustedFieldPath = new ArrayList<>(
                        parentPath.subList(targetPlan.lookupEntryPath.size(), parentPath.size()));
                }
                adjustedFieldPath.add(fieldName);
                SelectedValue transformedSelection = ResponseKeys.transformSelectionWithResponseKeys(
                    req.selection(), reqResponseKeys);
                String variable = targetPlan.addRequirementVariable(req.argumentName(), transformedSelection,
                    req.argumentType());
                targetPlan.addFieldArgument(adjustedFieldPath, req.argumentName(), variable);
            }
        }
    }

    /**
     * A field the planner adds itself (a lookup key, a required field) can have @require arguments of its own,
     * like one the client selects: those requirements are resolved for it too.
     */
    void processRequirementsOfAddedField(SubgraphPlan plan, Path path, List<String> parentPath) {
        LookupMoveEdge lookupEdge = plan.lookupEdge;
        if (lookupEdge == null || path.segments().isEmpty() || path.hasInitialTypeCondition()
            || !plan.adjustPath(parentPath).isEmpty()) {
            return;
        }
        String fieldName = path.segments().get(0).fieldName();
        if (lookupEdge.requires().stream().noneMatch(r -> fieldName.equals(r.fieldName()))
            || !requirementsOfAddedFields.add(plan.id + "|" + fieldName)) {
            return;
        }
        SubgraphPlan sourcePlan = findExistingPlanForSubgraph(lookupEdge.source().subgraph(), plan.lookupEntryPath);
        if (sourcePlan == null) {
            sourcePlan = context.getOrCreateRootPlan(lookupEdge.source().subgraph());
        }
        processRequireFields(lookupEdge, fieldName, plan.lookupEntryPath, sourcePlan, plan);
    }

    /**
     * Checks if a field path can be resolved from a given subgraph and type.
     * Used to determine where @require fields should be added.
     */
    boolean canResolveFieldPathInSubgraph(String subgraph, String typeName, Path path) {
        Node node = new Node(typeName, subgraph);
        return canResolveFieldPathFromNode(node, path);
    }

    /**
     * Checks if a field path (potentially nested) can be resolved starting from a node.
     * Handles type-conditioned paths like "relatedMedia&lt;Movie&gt;.imdbCode" by
     * narrowing to the specified type when a segment has a type condition.
     * Also checks implementing types when a field is not found on an interface.
     */
    private boolean canResolveFieldPathFromNode(Node startNode, Path path) {
        List<PathSegment> segments = path.segments();
        Node currentNode = startNode;
        String subgraph = startNode.subgraph();

        for (PathSegment segment : segments) {
            String fieldName = segment.fieldName();
            var matchingEdge = graph.fieldEdgesFrom(currentNode)
                .filter(edge -> edge.fieldName().equals(fieldName))
                .findFirst();

            if (matchingEdge.isEmpty()) {
                // Field not found on current type - check if it exists on implementing types
                // This handles cases like @require(field: "data.bar") where data returns
                // interface Foo, and bar is a field on implementing type Bar
                Set<String> implTypes = graph.getImplementingTypesForInterface(currentNode.typeName());
                boolean foundOnImplementingType = false;
                Node nextNode = null;
                for (String implType : implTypes) {
                    Node implNode = new Node(implType, subgraph);
                    var implEdge = graph.fieldEdgesFrom(implNode)
                        .filter(e -> e.fieldName().equals(fieldName))
                        .findFirst();
                    if (implEdge.isPresent()) {
                        foundOnImplementingType = true;
                        nextNode = implEdge.get().target();
                        break;
                    }
                }
                if (!foundOnImplementingType) {
                    return false;
                }
                currentNode = nextNode;
            } else {
                currentNode = matchingEdge.get().target();
            }

            // If segment has a type condition, narrow to that type
            // e.g., for "relatedMedia<Movie>.imdbCode", after resolving relatedMedia
            // (which returns Media), narrow to Movie to check if imdbCode exists
            if (segment.hasTypeCondition()) {
                currentNode = new Node(segment.typeCondition(), subgraph);
            }
        }

        return true;
    }

    /**
     * Result of resolving an @require field, containing the plan where it was added
     * and the response key to use for extracting the value.
     */
    record RequireFieldResolution(
        SubgraphPlan plan,
        String subgraph,
        Map<String, String> fieldToResponseKey
    ) {}

    /**
     * Resolves an @require field, potentially creating intermediate lookup steps.
     * This method handles chains where A requires lookup B, and B requires lookup C.
     *
     * The algorithm uses a two-pass approach:
     * 1. First pass: try all lookup edges to find DIRECT resolutions (target can resolve the field)
     * 2. Second pass: only if no direct resolution found, try recursive multi-hop resolution
     *
     * This ensures we find B->C before exploring B->A->B->C.
     *
     * @param sourceSubgraph the subgraph we're starting from
     * @param sourceTypeName the type name at the source
     * @param path the field path to resolve (e.g., Path for "price" or "dimension.weight")
     * @param parentPath the parent path in the response tree
     * @param visitedSubgraphs subgraphs already visited (to prevent cycles)
     * @return resolution containing the plan and response keys, or null if not resolvable
     */
    RequireFieldResolution resolveRequireField(
            String sourceSubgraph,
            String sourceTypeName,
            Path path,
            List<String> parentPath,
            Set<String> visitedSubgraphs) {
        return resolveRequireField(sourceSubgraph, sourceTypeName, path, parentPath, visitedSubgraphs, null);
    }

    /** Whether the source subgraph has a key path on the type or one of its possible types. */
    boolean keyInSourceSubgraph(String subgraph, String typeName, Path path) {
        if (canResolveFieldPathInSubgraph(subgraph, typeName, path)) {
            return true;
        }
        Set<String> possibleTypes = new HashSet<>(graph.getImplementingTypesForInterface(typeName));
        possibleTypes.addAll(graph.getUnionMembers(typeName));
        return possibleTypes.stream().anyMatch(type -> canResolveFieldPathInSubgraph(subgraph, type, path));
    }

    /** Fails planning for a requirement that no subgraph can provide, instead of omitting its value. */
    static RequireFieldResolution resolved(RequireFieldResolution resolution, Path path, String typeName) {
        if (resolution == null) {
            String fields = path.segments().stream().map(PathSegment::fieldName).collect(Collectors.joining("."));
            throw new PlanningException("Cannot resolve required field '" + fields + "' of " + typeName);
        }
        return resolution;
    }

    /**
     * Resolves a required field path from an object of {@code sourceTypeName} in {@code sourceSubgraph}: in the
     * plan that selects the object if the subgraph has the path; else through a lookup into a subgraph that has it,
     * or a chain of lookups (A -> B -> C); else below the path's first field.
     *
     * @param hopPlan the plan of the lookup that reaches {@code sourceSubgraph} when it is a hop of a chain
     *                (created on first use), or null to use the plan found for the subgraph and path
     */
    RequireFieldResolution resolveRequireField(
            String sourceSubgraph,
            String sourceTypeName,
            Path path,
            List<String> parentPath,
            Set<String> visitedSubgraphs,
            Supplier<SubgraphPlan> hopPlan) {

        if (canResolveFieldPathInSubgraph(sourceSubgraph, sourceTypeName, path)) {
            SubgraphPlan sourcePlan = sourcePlan(sourceSubgraph, sourceTypeName, parentPath, hopPlan);
            // The source type lets fields of implementing types go into inline fragments
            Map<String, String> responseKeys = context.addNestedFieldPathWithSubgraph(
                sourcePlan, path, parentPath, FieldOrigin.ARTIFICIAL_REQUIRE, sourceSubgraph, sourceTypeName);
            return new RequireFieldResolution(sourcePlan, sourceSubgraph, responseKeys);
        }

        // Through a lookup: first into the subgraphs that have the path, then chains through the others (B -> C
        // before B -> A -> B -> C)
        List<LookupMoveEdge> lookupEdges = graph.lookupEdgesFrom(new Node(sourceTypeName, sourceSubgraph))
            .filter(edge -> !visitedSubgraphs.contains(edge.target().subgraph()))
            .toList();
        for (boolean direct : new boolean[] {true, false}) {
            for (LookupMoveEdge lookupEdge : lookupEdges) {
                String targetSubgraph = lookupEdge.target().subgraph();
                String targetTypeName = lookupEdge.target().typeName();
                if (canResolveFieldPathInSubgraph(targetSubgraph, targetTypeName, path) != direct) {
                    continue;
                }
                Set<String> visitedThere = new HashSet<>(visitedSubgraphs);
                visitedThere.add(targetSubgraph);
                SubgraphPlan[] lookupPlan = new SubgraphPlan[1];
                RequireFieldResolution resolution = resolveRequireField(targetSubgraph, targetTypeName, path,
                    parentPath, visitedThere, () -> lookupPlan[0] != null ? lookupPlan[0]
                        : (lookupPlan[0] = enterThroughLookup(sourceSubgraph, sourceTypeName, lookupEdge, parentPath,
                            hopPlan)));
                if (resolution != null) {
                    return resolution;
                }
            }
        }

        return resolveRequireFieldBelowFirstField(sourceSubgraph, sourceTypeName, path, parentPath, visitedSubgraphs,
            hopPlan);
    }

    /**
     * The plan that selects the object at {@code parentPath} in a subgraph: the hop's lookup plan in a chain, else
     * the plan found for the subgraph and path, else the subgraph's root plan.
     */
    private SubgraphPlan sourcePlan(String subgraph, String typeName, List<String> parentPath,
                                    Supplier<SubgraphPlan> hopPlan) {
        if (hopPlan != null) {
            return hopPlan.get();
        }
        SubgraphPlan plan = findExistingPlanForSubgraph(subgraph, parentPath, typeName);
        return plan != null ? plan : context.getOrCreateRootPlan(subgraph);
    }

    /**
     * The plan of a lookup from the object at {@code parentPath} in {@code sourceSubgraph}: it waits for the plan
     * that selects the object, which selects the lookup's keys.
     */
    private SubgraphPlan enterThroughLookup(String sourceSubgraph, String sourceTypeName, LookupMoveEdge lookupEdge,
                                            List<String> parentPath, Supplier<SubgraphPlan> hopPlan) {
        SubgraphPlan source = sourcePlan(sourceSubgraph, sourceTypeName, parentPath, hopPlan);
        SubgraphPlan lookupPlan = context.getOrCreateLookupPlan(lookupEdge, parentPath);
        context.addPlanDependency(lookupPlan, source);
        context.addLookupKeys(lookupEdge, parentPath, lookupPlan,
            keyPath -> context.addKeyPath(source, keyPath, parentPath, lookupEdge));
        return lookupPlan;
    }

    /**
     * Resolves a requirement whose first field is in the source subgraph but whose rest is not, from the
     * objects that first field returns: for {@code comments[somethingElse]}, {@code comments} is selected
     * here and {@code somethingElse} is resolved for each comment (e.g. through a lookup on Comment).
     * Returns null if that does not resolve it either.
     */
    private RequireFieldResolution resolveRequireFieldBelowFirstField(
            String sourceSubgraph,
            String sourceTypeName,
            Path path,
            List<String> parentPath,
            Set<String> visitedSubgraphs,
            Supplier<SubgraphPlan> hopPlan) {
        List<PathSegment> segments = path.segments();
        if (segments.size() < 2 || path.hasInitialTypeCondition() || segments.get(0).typeCondition() != null) {
            return null;
        }
        String firstField = segments.get(0).fieldName();
        var firstEdge = graph.fieldEdgesFrom(new Node(sourceTypeName, sourceSubgraph))
            .filter(e -> e.fieldName().equals(firstField))
            .findFirst();
        if (firstEdge.isEmpty()) {
            return null;
        }
        SubgraphPlan sourcePlan = sourcePlan(sourceSubgraph, sourceTypeName, parentPath, hopPlan);
        String responseKey = sourcePlan.addArtificialField(firstField, parentPath, true,
            FieldOrigin.ARTIFICIAL_REQUIRE);
        List<String> childPath = new ArrayList<>(parentPath);
        childPath.add(responseKey);
        RequireFieldResolution rest = resolveRequireField(sourceSubgraph, firstEdge.get().target().typeName(),
            new Path(segments.subList(1, segments.size())), childPath, visitedSubgraphs);
        if (rest == null) {
            return null;
        }
        Map<String, String> responseKeys = new LinkedHashMap<>();
        responseKeys.put(ResponseKeys.segmentKey(segments.get(0)), responseKey);
        responseKeys.putAll(rest.fieldToResponseKey());
        return new RequireFieldResolution(rest.plan(), rest.subgraph(), responseKeys);
    }

    /**
     * Finds an existing plan for a subgraph at the given response path.
     * Prefers the most specific lookup plan containing the path, then the root plan.
     * Returns null if no plan exists.
     */
    SubgraphPlan findExistingPlanForSubgraph(String subgraph, List<String> parentPath) {
        return findExistingPlanForSubgraph(subgraph, parentPath, null);
    }

    /**
     * As above, for an object of {@code typeName} (null: any): a lookup plan whose entity is the object at
     * {@code parentPath} only qualifies if it resolves that type (two branches of a union or interface can have
     * lookups into the same subgraph at the same path, one per type).
     */
    SubgraphPlan findExistingPlanForSubgraph(String subgraph, List<String> parentPath, String typeName) {
        // Prefer the most specific plan that already selects the object at the path: requirements of a nested
        // object go to the plan that fetched it, not to a later lookup whose entry path is merely a prefix
        SubgraphPlan containing = null;
        int containingLength = -1;
        for (var entry : context.subgraphPlans.entrySet()) {
            PlanningContext.SubgraphPlanKey key = entry.getKey();
            if (key.subgraph().equals(subgraph) && !waitsForRequirementTarget(entry.getValue())
                && resolvesType(entry.getValue(), parentPath, typeName)
                && isPathPrefix(key.entryPath(), parentPath) && key.entryPath().size() > containingLength
                && entry.getValue().selectsPath(parentPath)) {
                containing = entry.getValue();
                containingLength = key.entryPath().size();
            }
        }
        if (containing != null) {
            return containing;
        }

        SubgraphPlan rootPlan = null;
        SubgraphPlan bestLookupPlan = null;
        int bestLookupPathLength = -1;

        for (var entry : context.subgraphPlans.entrySet()) {
            PlanningContext.SubgraphPlanKey key = entry.getKey();
            if (!key.subgraph().equals(subgraph) || waitsForRequirementTarget(entry.getValue())
                || !resolvesType(entry.getValue(), parentPath, typeName)) {
                continue;
            }
            if (key.isRootEntry() && key.rootGroup() != context.rootGroup()) {
                continue;  // a root plan of an earlier group of a mutation's top-level fields
            }
            if (key.isRootEntry()) {
                rootPlan = entry.getValue();
                continue;
            }
            if (isPathPrefix(key.entryPath(), parentPath) && key.entryPath().size() > bestLookupPathLength) {
                bestLookupPlan = entry.getValue();
                bestLookupPathLength = key.entryPath().size();
            }
        }

        return bestLookupPlan != null ? bestLookupPlan : rootPlan;
    }

    /**
     * Whether a plan (transitively) waits for a plan whose requirements are being resolved: it cannot provide
     * them without a cycle.
     */
    private boolean waitsForRequirementTarget(SubgraphPlan plan) {
        if (requirementTargets.isEmpty()) {
            return false;
        }
        Set<Integer> targets = new HashSet<>();
        requirementTargets.forEach(target -> targets.add(target.id));
        Deque<Integer> pending = new ArrayDeque<>(List.of(plan.id));
        Set<Integer> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            int id = pending.pop();
            if (!seen.add(id)) {
                continue;
            }
            for (int dependency : context.planDependencies.getOrDefault(id, Set.of())) {
                if (targets.contains(dependency)) {
                    return true;
                }
                pending.push(dependency);
            }
        }
        return false;
    }

    private boolean resolvesType(SubgraphPlan plan, List<String> parentPath, String typeName) {
        return typeName == null || plan.lookupTargetType() == null || !parentPath.equals(plan.lookupEntryPath)
            || graph.concreteTypes(plan.lookupTargetType()).contains(typeName)
            || graph.concreteTypes(typeName).contains(plan.lookupTargetType());
    }

    private boolean isPathPrefix(List<String> prefix, List<String> path) {
        if (prefix == null || path == null || prefix.size() > path.size()) {
            return false;
        }
        for (int i = 0; i < prefix.size(); i++) {
            if (!prefix.get(i).equals(path.get(i))) {
                return false;
            }
        }
        return true;
    }
}
