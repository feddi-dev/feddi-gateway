package dev.feddi.federation.engine.planner;

import dev.feddi.federation.engine.Constants;

import dev.feddi.federation.engine.IntrospectionFields;
import dev.feddi.federation.engine.graph.Edge;
import dev.feddi.federation.engine.graph.Graph;
import dev.feddi.federation.engine.graph.LookupArgument;
import dev.feddi.federation.engine.graph.LookupMoveEdge;
import dev.feddi.federation.engine.graph.Node;
import dev.feddi.federation.engine.graph.Requirement;
import dev.feddi.federation.engine.query.FieldSelection;
import dev.feddi.federation.engine.query.InlineFragmentSelection;
import dev.feddi.federation.engine.query.Operation;
import dev.feddi.federation.engine.query.Selection;
import dev.feddi.federation.engine.parser.FieldSelectionMap.Alternative;
import dev.feddi.federation.engine.parser.FieldSelectionMap.ObjectSelection;
import dev.feddi.federation.engine.parser.FieldSelectionMap.Path;
import dev.feddi.federation.engine.parser.FieldSelectionMap.PathSegment;
import dev.feddi.federation.engine.parser.FieldSelectionMap.SelectedValue;
import dev.feddi.federation.engine.parser.FieldSelectionMapParser;
import dev.feddi.federation.engine.parser.InvalidSyntaxException;
import graphql.language.Argument;
import graphql.language.Directive;
import graphql.language.Field;
import graphql.language.InlineFragment;
import graphql.language.OperationDefinition;
import graphql.language.SelectionSet;
import graphql.language.Type;
import graphql.language.TypeName;
import graphql.language.VariableDefinition;
import graphql.language.VariableReference;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Main query planner that orchestrates the planning process.
 * Takes a Graph and Operation, produces an ExecutionPlan.
 */
public final class OperationPlanner {
    
    private final Graph graph;
    private final PathFinder pathFinder;
    
    public OperationPlanner(Graph graph) {
        this.graph = graph;
        this.pathFinder = new PathFinder(graph);
    }
    
    /**
     * Plans the execution of a query.
     *
     * @param query the query to plan
     * @return the execution plan
     */
    public ExecutionPlan plan(Operation query) {
        // Find the root node for the query's operation type (Query or Mutation)
        Node rootNode = graph.getRootNode(query.rootType());
        if (rootNode == null) {
            throw new IllegalArgumentException(
                "No root node found for operation type '" + query.rootType() + "'"
            );
        }

        // Start planning from the root
        OperationPath rootPath = OperationPath.startAt(rootNode);

        // Determine the operation type for subgraph operations
        OperationDefinition.Operation operationType = Constants.MUTATION.equals(query.rootType())
            ? OperationDefinition.Operation.MUTATION
            : OperationDefinition.Operation.QUERY;

        // Collect all planning results, with query variable definitions for pass-through
        PlanningContext context = new PlanningContext(query.variableDefinitions(), operationType, graph);

        // Plan each top-level selection
        for (Selection selection : query.selections()) {
            planSelection(rootPath, selection, List.of(), context);
        }

        // Build the execution plan from the planning context
        return context.buildPlan();
    }
    
    /**
     * Plans a single selection (field or inline fragment) recursively.
     *
     * @param currentPath the current path through the graph
     * @param selection the selection to plan
     * @param parentPath the path of parent field names leading to this selection (for hierarchy tracking)
     * @param context the planning context
     */
    private void planSelection(OperationPath currentPath, Selection selection,
                               List<String> parentPath, PlanningContext context) {
        planSelection(currentPath, selection, parentPath, context, null);
    }

    /**
     * Plans a single selection with optional inline fragment context.
     * When fragmentContext is provided, fields are added to that fragment's selection tree.
     */
    private void planSelection(OperationPath currentPath, Selection selection,
                               List<String> parentPath, PlanningContext context,
                               InlineFragmentContext fragmentContext) {
        if (selection instanceof FieldSelection fieldSelection) {
            planFieldSelection(currentPath, fieldSelection, parentPath, context, fragmentContext);
        } else if (selection instanceof InlineFragmentSelection inlineFragment) {
            // Nested inline fragments start fresh - they're handled separately
            planInlineFragment(currentPath, inlineFragment, parentPath, context);
        }
    }
    /**
     * Plans a field selection with optional inline fragment context.
     * When fragmentContext is provided, the field is added to the fragment's selection tree.
     */
    private void planFieldSelection(OperationPath currentPath, FieldSelection selection,
                                    List<String> parentPath, PlanningContext context,
                                    InlineFragmentContext fragmentContext) {
        String fieldName = selection.fieldName();

        // Find paths to resolve this field
        List<OperationPath> paths = pathFinder.findPaths(currentPath, fieldName);

        if (paths.isEmpty()) {
            throw new PlanningException("Cannot find path to resolve field: " + fieldName);
        }

        // Use the best path (lowest cost)
        OperationPath bestPath = paths.get(0);

        // Check if this field routes to the introspection subgraph
        boolean isIntrospectionField = "$introspection".equals(bestPath.currentSubgraph());

        // Introspection fields are handled opaquely - store the entire selection tree
        if (isIntrospectionField) {
            context.recordOpaqueFieldResolution(bestPath, selection, parentPath);
            return;
        }

        if (fragmentContext != null) {
            // Add to inline fragment's selection tree
            fragmentContext.addField(selection.alias(), fieldName, selection.hasSubSelections(),
                selection.arguments(), selection.directives());
            // Also record for dependency tracking
            context.recordFieldResolutionForFragment(bestPath, fieldName, parentPath,
                selection.hasSubSelections(), selection.arguments(), selection.directives(),
                fragmentContext);
        } else {
            // Add to regular selection tree
            context.recordFieldResolution(bestPath, selection.alias(), fieldName, parentPath,
                selection.hasSubSelections(), selection.arguments(), selection.directives());
        }

        // Recursively plan sub-selections, passing fragment context to keep fields inside the fragment
        if (selection.hasSubSelections()) {
            List<String> childParentPath = new ArrayList<>(parentPath);
            childParentPath.add(fieldName);

            // Enter the field in the fragment context for nested path tracking
            if (fragmentContext != null) {
                fragmentContext.enterField(fieldName);
            }

            for (Selection subSelection : selection.subSelections()) {
                planSelection(bestPath, subSelection, childParentPath, context, fragmentContext);
            }

            // Exit the field in the fragment context
            if (fragmentContext != null) {
                fragmentContext.exitField();
            }
        }
    }

    /**
     * Plans an inline fragment by planning its selections with the type context.
     * The type condition affects which fields can be resolved (polymorphic selection).
     */
    private void planInlineFragment(OperationPath currentPath, InlineFragmentSelection fragment,
                                    List<String> parentPath, PlanningContext context) {
        String typeCondition = fragment.typeCondition();

        // Check if the type condition matches the current node's type
        // If so, the inline fragment is redundant and we can just plan fields directly
        // e.g., "... on Content { id }" when we're at Content is equivalent to just "{ id }"
        String currentTypeName = currentPath.tail().typeName();
        boolean typeConditionMatchesCurrentType = typeCondition != null && typeCondition.equals(currentTypeName);

        if (typeConditionMatchesCurrentType) {
            // Type condition matches current type - plan fields directly without inline fragment
            for (Selection subSelection : fragment.subSelections()) {
                planSelection(currentPath, subSelection, parentPath, context);
            }
            return;
        }

        // When we have an inline fragment with a type condition that differs from the
        // current type (e.g., "... on Book" when at Media union), we need __typename
        // at the parent level to determine which inline fragment to apply at runtime.
        if (typeCondition != null) {
            context.addTypenameToSubgraph(currentPath.currentSubgraph(), parentPath);
        }

        // Narrow the path to the concrete type so field lookups use the correct node
        // e.g., when at Content/content and entering "... on Article", use Article for field lookup
        OperationPath narrowedPath = typeCondition != null
            ? currentPath.withTypeContext(typeCondition)
            : currentPath;

        // Don't eagerly create inline fragment context - it will be created on demand
        // when we find fields that resolve in the same subgraph. This avoids creating
        // empty inline fragments when all fields go to a different subgraph.

        // Plan each selection within the fragment
        for (Selection subSelection : fragment.subSelections()) {
            if (subSelection instanceof FieldSelection fieldSelection) {
                // Add field to the inline fragment's selection set using narrowed path
                planFieldSelectionInFragment(narrowedPath, fieldSelection, parentPath, context,
                    typeCondition, fragment.directives());
            } else if (subSelection instanceof InlineFragmentSelection nestedFragment) {
                // Nested inline fragments are rare but possible
                planInlineFragment(narrowedPath, nestedFragment, parentPath, context);
            }
        }
    }

    /**
     * Plans a field selection within an inline fragment context.
     * Handles cross-subgraph inline fragments by creating the fragment in the target subgraph.
     */
    private void planFieldSelectionInFragment(OperationPath currentPath, FieldSelection selection,
                                              List<String> parentPath, PlanningContext context,
                                              String typeCondition, List<Directive> fragmentDirectives) {
        String fieldName = selection.fieldName();

        // Find paths to resolve this field
        List<OperationPath> paths = pathFinder.findPaths(currentPath, fieldName);

        if (paths.isEmpty()) {
            throw new PlanningException("Cannot find path to resolve field: " + fieldName);
        }

        // Use the best path (lowest cost)
        OperationPath bestPath = paths.get(0);

        // Determine which subgraph the field is resolved in
        String targetSubgraph = bestPath.currentSubgraph();
        String sourceSubgraph = currentPath.currentSubgraph();

        // Get or create inline fragment context in the appropriate subgraph
        InlineFragmentContext targetFragmentContext;
        if (!targetSubgraph.equals(sourceSubgraph)) {
            // Cross-subgraph inline fragment: add the fragment to the target subgraph's plan
            targetFragmentContext = context.getOrCreateInlineFragmentForLookup(
                bestPath, parentPath, typeCondition, fragmentDirectives);
        } else {
            // Same subgraph: create fragment context on demand (lazy creation)
            targetFragmentContext = context.getInlineFragmentContext(
                sourceSubgraph, parentPath, typeCondition, fragmentDirectives);
        }

        // Add field to the inline fragment's selection set
        if (targetFragmentContext != null) {
            targetFragmentContext.addField(selection.alias(), fieldName, selection.hasSubSelections(),
                selection.arguments(), selection.directives());
        }

        // Also record field resolution for dependency tracking (lookups, etc.)
        context.recordFieldResolutionForFragment(bestPath, fieldName, parentPath, selection.hasSubSelections(),
            selection.arguments(), selection.directives(), targetFragmentContext);

        // Recursively plan sub-selections, passing the fragment context
        // so nested fields stay inside the inline fragment's selection tree
        if (selection.hasSubSelections()) {
            List<String> childParentPath = new ArrayList<>(parentPath);
            childParentPath.add(fieldName);

            // Enter the field in the fragment context for nested path tracking
            if (targetFragmentContext != null) {
                targetFragmentContext.enterField(fieldName);
            }

            for (Selection subSelection : selection.subSelections()) {
                planSelection(bestPath, subSelection, childParentPath, context, targetFragmentContext);
            }

            // Exit the field in the fragment context
            if (targetFragmentContext != null) {
                targetFragmentContext.exitField();
            }
        }
    }
    
    /**
     * Key for identifying subgraph plans. Each unique combination of subgraph,
     * entering lookup edge, and lookup entry path gets its own plan. This allows
     * multiple plans per subgraph (e.g., root query + lookups, or the same lookup
     * reached from different response branches).
     *
     * @param subgraph the subgraph name
     * @param entryLookup the lookup edge used to enter this subgraph, or null for root queries
     */
    private record SubgraphPlanKey(String subgraph, LookupMoveEdge entryLookup, List<String> entryPath) {
        SubgraphPlanKey {
            entryPath = entryPath == null ? List.of() : List.copyOf(entryPath);
        }

        static SubgraphPlanKey forRoot(String subgraph) {
            return new SubgraphPlanKey(subgraph, null, List.of());
        }

        static SubgraphPlanKey forLookup(String subgraph, LookupMoveEdge entryLookup, List<String> entryPath) {
            return new SubgraphPlanKey(subgraph, entryLookup, entryPath);
        }

        boolean isRootEntry() {
            return entryLookup == null;
        }
    }

    private record LookupPlanKey(LookupMoveEdge lookupEdge, List<String> entryPath) {
        LookupPlanKey {
            entryPath = entryPath == null ? List.of() : List.copyOf(entryPath);
        }
    }

    /**
     * Context for collecting planning results.
     */
    private static class PlanningContext {
        // Plans keyed by (subgraph, entryLookup, entryPath) to allow multiple plans per subgraph
        private final Map<SubgraphPlanKey, SubgraphPlan> subgraphPlans = new LinkedHashMap<>();

        // Dependencies between plans, keyed by plan ID
        private final Map<Integer, Set<Integer>> planDependencies = new HashMap<>();

        private final AtomicInteger stepIdGenerator = new AtomicInteger(0);

        // Track which lookup entries have had their lookup arguments added to avoid duplicates
        private final Set<LookupPlanKey> processedLookupEdges = new HashSet<>();

        // Track response keys for @require fields by lookup edge
        // Maps LookupPlanKey -> (fieldName -> responseKey)
        private final Map<LookupPlanKey, Map<String, String>> requireFieldResponseKeys = new HashMap<>();

        // Map from lookup edge plus response entry path to the plan that was created for it
        private final Map<LookupPlanKey, SubgraphPlan> lookupEdgeToPlan = new HashMap<>();

        // Query-level variable definitions for pass-through to subgraph operations
        private final List<VariableDefinition> queryVariableDefinitions;

        // The operation type (QUERY or MUTATION) for building subgraph operations
        private final OperationDefinition.Operation operationType;

        // Graph reference for checking field resolution
        private final Graph graph;

        PlanningContext(List<VariableDefinition> queryVariableDefinitions, OperationDefinition.Operation operationType, Graph graph) {
            this.queryVariableDefinitions = queryVariableDefinitions != null
                ? queryVariableDefinitions : List.of();
            this.operationType = operationType;
            this.graph = graph;
        }

        /**
         * Gets or creates a plan for a root query (no lookup entry).
         */
        SubgraphPlan getOrCreateRootPlan(String subgraph) {
            SubgraphPlanKey key = SubgraphPlanKey.forRoot(subgraph);
            return subgraphPlans.computeIfAbsent(key, k ->
                new SubgraphPlan(stepIdGenerator.incrementAndGet(), subgraph, queryVariableDefinitions, operationType));
        }

        /**
         * Gets or creates a plan for a lookup entry.
         */
        SubgraphPlan getOrCreateLookupPlan(LookupMoveEdge lookupEdge, List<String> parentPath) {
            LookupPlanKey lookupPlanKey = new LookupPlanKey(lookupEdge, parentPath);

            // Check if we already have a plan for this lookup edge at this response path
            SubgraphPlan existingPlan = lookupEdgeToPlan.get(lookupPlanKey);
            if (existingPlan != null) {
                return existingPlan;
            }

            String subgraph = lookupEdge.target().subgraph();
            SubgraphPlanKey key = SubgraphPlanKey.forLookup(subgraph, lookupEdge, parentPath);
            SubgraphPlan plan = subgraphPlans.computeIfAbsent(key, k -> {
                SubgraphPlan newPlan = new SubgraphPlan(stepIdGenerator.incrementAndGet(), subgraph, queryVariableDefinitions, operationType);
                newPlan.setLookupOrigin(lookupEdge, parentPath);
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

        private List<String> lookupEntryPath(OperationPath path, LookupMoveEdge lookupEdge, List<String> fallbackParentPath) {
            List<String> entryPath = new ArrayList<>();
            for (Edge edge : path.getEdges()) {
                if (edge.equals(lookupEdge)) {
                    return entryPath;
                }
                if (!(edge instanceof LookupMoveEdge)) {
                    entryPath.add(edge.fieldName());
                }
            }
            return fallbackParentPath;
        }
        
        /**
         * Records a field resolution with hierarchical information.
         */
        void recordFieldResolution(OperationPath path, String alias, String fieldName,
                                   List<String> parentPath, boolean hasChildren,
                                   List<Argument> queryArguments, List<Directive> queryDirectives) {
            String subgraph = path.currentSubgraph();

            // Find the last lookup edge that enters the current subgraph (if any)
            LookupMoveEdge enteringLookupEdge = null;
            for (Edge edge : path.getEdges()) {
                if (edge instanceof LookupMoveEdge lookupEdge
                    && lookupEdge.target().subgraph().equals(subgraph)) {
                    enteringLookupEdge = lookupEdge;
                }
            }

            // Get or create the appropriate plan for this field
            SubgraphPlan plan;
            if (enteringLookupEdge != null) {
                List<String> entryPath = lookupEntryPath(path, enteringLookupEdge, parentPath);
                plan = getOrCreateLookupPlan(enteringLookupEdge, entryPath);
            } else {
                plan = getOrCreateRootPlan(subgraph);
            }

            // Add the field to this subgraph's plan with its hierarchical position, arguments, and directives
            plan.addFieldWithAlias(alias, fieldName, parentPath, hasChildren, queryArguments, queryDirectives);

            // Process all lookup edges in the path to set up dependencies and key fields
            processLookupEdgesInPath(path, fieldName, parentPath, plan);
        }

        /**
         * Processes lookup edges in a path to set up dependencies and key fields.
         */
        private void processLookupEdgesInPath(OperationPath path, String fieldName,
                                               List<String> parentPath, SubgraphPlan targetPlan) {
            String targetSubgraph = targetPlan.subgraph;

            // Build a chain of lookup edges and their corresponding plans
            // Each lookup's source needs to have key fields added
            List<LookupMoveEdge> lookupChain = new ArrayList<>();
            for (Edge edge : path.getEdges()) {
                if (edge instanceof LookupMoveEdge lookupEdge) {
                    lookupChain.add(lookupEdge);
                }
            }

            // Process each lookup edge in the chain
            for (int i = 0; i < lookupChain.size(); i++) {
                LookupMoveEdge lookupEdge = lookupChain.get(i);
                String sourceSubgraph = lookupEdge.source().subgraph();
                List<String> entryPath = lookupEntryPath(path, lookupEdge, parentPath);

                // Find the source plan - it's either a previous lookup's target or the root plan
                SubgraphPlan sourcePlan;
                if (i > 0) {
                    // Source is the previous lookup's target
                    LookupMoveEdge prevLookup = lookupChain.get(i - 1);
                    List<String> prevEntryPath = lookupEntryPath(path, prevLookup, parentPath);
                    sourcePlan = lookupEdgeToPlan.get(new LookupPlanKey(prevLookup, prevEntryPath));
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

                // Only process key fields once per lookup entry to avoid duplicates
                LookupPlanKey lookupPlanKey = new LookupPlanKey(lookupEdge, entryPath);
                if (!processedLookupEdges.contains(lookupPlanKey)) {
                    processedLookupEdges.add(lookupPlanKey);

                    // Add key fields to the SOURCE plan and collect response key maps for each field
                    // Note: parentPath is used directly - SubgraphPlan.adjustPath() handles lookup prefix stripping
                    // Process ALL paths from all alternatives (e.g., <Book>.isbn | <Electronics>.sku)
                    Map<String, Map<String, String>> lookupArgNestedResponseKeys = new HashMap<>();
                    for (LookupArgument lookupArg :lookupEdge.lookupArguments()) {
                        Map<String, String> combinedResponseKeys = new LinkedHashMap<>();
                        for (Path altPath : lookupArg.extractPaths()) {
                            Map<String, String> nestedResponseKeys = addNestedFieldPath(sourcePlan, altPath, entryPath, FieldOrigin.ARTIFICIAL_KEY);
                            combinedResponseKeys.putAll(nestedResponseKeys);
                        }
                        lookupArgNestedResponseKeys.put(lookupArg.argumentName(), combinedResponseKeys);
                    }

                    // Add key field requirements to the target plan
                    for (LookupArgument lookupArg :lookupEdge.lookupArguments()) {
                        Map<String, String> nestedResponseKeys = lookupArgNestedResponseKeys.get(lookupArg.argumentName());
                        SelectedValue requirement = createRequirementSelectedValue(lookupArg, nestedResponseKeys);
                        lookupTargetPlan.addRequirement(lookupArg.argumentName(), requirement, lookupArg.argumentType());
                    }
                }

                // Process @require fields for the last lookup in the chain that enters the target subgraph
                if (lookupEdge.target().subgraph().equals(targetSubgraph) && lookupEdge.hasRequirements()) {
                    processRequireFields(lookupEdge, fieldName, parentPath, sourcePlan, lookupTargetPlan);
                }
            }
        }

        /**
         * Processes @require fields for a lookup edge.
         */
        private void processRequireFields(LookupMoveEdge lookupEdge, String fieldName,
                                          List<String> parentPath, SubgraphPlan sourcePlan,
                                          SubgraphPlan targetPlan) {
            String sourceSubgraph = lookupEdge.source().subgraph();
            String sourceTypeName = lookupEdge.source().typeName();

            Set<String> visitedForRequire = new HashSet<>();
            visitedForRequire.add(targetPlan.subgraph); // Don't resolve @require in the target subgraph itself

            // Get or create the response keys map for this lookup edge
            List<String> entryPath = targetPlan.lookupEntryPath != null ? targetPlan.lookupEntryPath : parentPath;
            Map<String, String> reqResponseKeys = requireFieldResponseKeys.computeIfAbsent(
                new LookupPlanKey(lookupEdge, entryPath), k -> new HashMap<>());

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
                    RequireFieldResolution resolution = resolveRequireField(
                        sourceSubgraph, sourceTypeName, fieldPath, reqParentPath, visitedForRequire);

                    if (resolution != null) {
                        reqResponseKeys.putAll(resolution.fieldToResponseKey());

                        // If resolved from a different plan, add dependency
                        if (resolution.plan().id != sourcePlan.id) {
                            addPlanDependency(targetPlan, resolution.plan());
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
                    targetPlan.addFieldArgument(adjustedFieldPath, req.argumentName(), req.argumentName());
                }

                // Add @require variable as requirement for the target plan
                SelectedValue transformedSelection = transformSelectionWithResponseKeys(
                    req.selection(), reqResponseKeys);
                targetPlan.addRequirement(req.argumentName(), transformedSelection, req.argumentType());
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
         * Adds __typename to a subgraph plan at the specified parent path.
         * This is needed when querying abstract types (unions/interfaces) with inline fragments
         * to determine which type condition applies at runtime.
         * The field is marked as artificial so it's stripped from the final response
         * unless the user explicitly requested it.
         */
        void addTypenameToSubgraph(String subgraph, List<String> parentPath) {
            SubgraphPlan plan = getOrCreateRootPlan(subgraph);
            plan.addField(IntrospectionFields.TYPENAME, parentPath, false, List.of(), List.of(), FieldOrigin.ARTIFICIAL_KEY);
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
        private Map<String, String> addNestedFieldPath(SubgraphPlan plan, Path path, List<String> parentPath, FieldOrigin origin) {
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
        private Map<String, String> addNestedFieldPathWithSubgraph(SubgraphPlan plan, Path path, List<String> parentPath,
                                                                     FieldOrigin origin, String subgraph, String startTypeName) {
            List<String> currentPath = new ArrayList<>(parentPath);
            Map<String, String> fieldToResponseKey = new LinkedHashMap<>();

            // If path has initial type condition (e.g., <Movie>.imdbCode), add __typename
            // at the start so the executor can verify the type at runtime
            if (path.hasInitialTypeCondition()) {
                plan.addArtificialField(IntrospectionFields.TYPENAME, currentPath, false, FieldOrigin.ARTIFICIAL_KEY);
            }

            List<PathSegment> pathSegments = path.segments();
            InlineFragmentNode inlineFragment = null;  // Track if we're inside an inline fragment
            List<String> fragmentPath = null;          // Path within the inline fragment
            String currentTypeName = startTypeName;    // Track current type for implementing type checks

            for (int i = 0; i < pathSegments.size(); i++) {
                PathSegment segment = pathSegments.get(i);
                String fieldName = segment.fieldName();
                boolean hasChildren = (i < pathSegments.size() - 1); // intermediate nodes have children

                // Check if field exists on the current type or needs inline fragments
                boolean needsInlineFragments = false;
                Set<String> implementingTypesWithField = new HashSet<>();

                if (currentTypeName != null && inlineFragment == null) {
                    Node currentNode = new Node(currentTypeName, subgraph);
                    var matchingEdge = graph.fieldEdgesFrom(currentNode)
                        .filter(edge -> edge.fieldName().equals(fieldName))
                        .findFirst();

                    if (matchingEdge.isEmpty()) {
                        // Field not on current type - find the most specific types that have it
                        // Priority: interfaces first (to avoid duplication), then concrete types
                        Set<String> implTypes = graph.getImplementingTypesForInterface(currentTypeName);
                        Set<String> interfacesWithField = new HashSet<>();
                        Set<String> concreteTypesWithField = new HashSet<>();

                        for (String implType : implTypes) {
                            Node implNode = new Node(implType, subgraph);
                            boolean hasField = graph.fieldEdgesFrom(implNode)
                                .anyMatch(e -> e.fieldName().equals(fieldName));
                            if (hasField) {
                                // Check if this type is an interface (has implementing types)
                                if (!graph.getImplementingTypesForInterface(implType).isEmpty()) {
                                    interfacesWithField.add(implType);
                                } else {
                                    concreteTypesWithField.add(implType);
                                }
                            }
                        }

                        // Use interfaces if available (they're more general)
                        // But only use concrete types that aren't covered by an interface
                        for (String iface : interfacesWithField) {
                            implementingTypesWithField.add(iface);
                            // Remove concrete types that implement this interface
                            Set<String> ifaceImpls = graph.getImplementingTypesForInterface(iface);
                            concreteTypesWithField.removeAll(ifaceImpls);
                        }
                        // Add remaining concrete types not covered by interfaces
                        implementingTypesWithField.addAll(concreteTypesWithField);

                        needsInlineFragments = !implementingTypesWithField.isEmpty();
                    } else {
                        // Update current type to the field's target type for next iteration
                        currentTypeName = matchingEdge.get().target().typeName();
                    }
                }

                if (needsInlineFragments) {
                    // Field exists on implementing types but not on the interface
                    // Add __typename for runtime type checking
                    plan.addArtificialField(IntrospectionFields.TYPENAME, currentPath, false, FieldOrigin.ARTIFICIAL_KEY);

                    // Create inline fragments for each implementing type that has the field
                    for (String implType : implementingTypesWithField) {
                        InlineFragmentNode fragment = plan.getOrCreateInlineFragment(currentPath, implType, List.of());
                        List<String> fragPath = new ArrayList<>();

                        // Add the current field and any remaining fields in the path
                        for (int j = i; j < pathSegments.size(); j++) {
                            PathSegment seg = pathSegments.get(j);
                            boolean segHasChildren = (j < pathSegments.size() - 1);
                            fragment.addFieldAtPath(null, seg.fieldName(), fragPath, segHasChildren,
                                List.of(), List.of(), origin);
                            fieldToResponseKey.put(seg.fieldName(), seg.fieldName());
                            if (segHasChildren) {
                                fragPath.add(seg.fieldName());
                            }
                        }
                    }

                    // All remaining fields have been added inside inline fragments, we're done
                    return fieldToResponseKey;
                }

                if (inlineFragment != null) {
                    // Add field inside inline fragment
                    inlineFragment.addFieldAtPath(null, fieldName, fragmentPath, hasChildren,
                        List.of(), List.of(), origin);
                    fieldToResponseKey.put(fieldName, fieldName);
                    fragmentPath.add(fieldName);
                } else {
                    // Add field normally to the plan
                    String responseKey = plan.addArtificialField(fieldName, currentPath, hasChildren, origin);
                    fieldToResponseKey.put(fieldName, responseKey);
                    currentPath.add(fieldName);
                }

                // If this segment has an infix type condition (e.g., relatedMedia<Movie>.imdbCode),
                // add __typename and create an inline fragment for subsequent fields.
                // The inline fragment ensures type-specific fields are fetched correctly
                // when the parent field returns an interface or union type.
                if (segment.hasTypeCondition()) {
                    if (inlineFragment != null) {
                        // Already inside an inline fragment, add __typename there
                        inlineFragment.addFieldAtPath(null, IntrospectionFields.TYPENAME, fragmentPath, false,
                            List.of(), List.of(), FieldOrigin.ARTIFICIAL_KEY);
                    } else {
                        // Add __typename at current path for runtime type checking
                        plan.addArtificialField(IntrospectionFields.TYPENAME, currentPath, false, FieldOrigin.ARTIFICIAL_KEY);
                    }

                    // If there are more segments after this one, create inline fragment
                    // for subsequent fields to be fetched inside the type condition
                    if (i < pathSegments.size() - 1) {
                        inlineFragment = plan.getOrCreateInlineFragment(currentPath, segment.typeCondition(), List.of());
                        fragmentPath = new ArrayList<>();
                    }
                    // Update current type to the type condition
                    currentTypeName = segment.typeCondition();
                }
            }

            return fieldToResponseKey;
        }

        /**
         * Creates a Path with response keys substituted for field names.
         * Used for building requirement paths where field names may be aliased.
         *
         * @param path the original path
         * @param fieldToResponseKey map from field names to their response keys
         * @return a Path with response keys substituted for field names
         */
        private Path createNestedPath(Path path, Map<String, String> fieldToResponseKey) {
            List<PathSegment> pathSegments = new ArrayList<>();
            for (PathSegment segment : path.segments()) {
                String fieldName = segment.fieldName();
                String responseKey = fieldToResponseKey.getOrDefault(fieldName, fieldName);
                pathSegments.add(new PathSegment(responseKey));
            }
            return new Path(path.initialTypeCondition(), pathSegments);
        }

        /**
         * Creates a SelectedValue for a requirement from a LookupArgument, applying response key mapping.
         * For single-path arguments, returns a single-path SelectedValue.
         * For multi-alternative arguments (e.g., {@code <Book>.isbn | <Electronics>.sku}),
         * returns a SelectedValue with all alternatives preserved.
         */
        private SelectedValue createRequirementSelectedValue(LookupArgument lookupArg, Map<String, String> nestedResponseKeys) {
            List<Path> paths = lookupArg.extractPaths();
            if (paths.size() == 1) {
                return new SelectedValue(createNestedPath(paths.get(0), nestedResponseKeys));
            }
            List<Alternative> alternatives = new ArrayList<>();
            for (Path path : paths) {
                alternatives.add(createNestedPath(path, nestedResponseKeys));
            }
            return new SelectedValue(alternatives);
        }

        /**
         * Gets the leaf (last) field name from a Path.
         */
        private String getLeafFieldName(Path path) {
            List<PathSegment> segments = path.segments();
            if (segments.isEmpty()) {
                return null;
            }
            return segments.get(segments.size() - 1).fieldName();
        }

        /**
         * Checks if a field path can be resolved from a given subgraph and type.
         * Used to determine where @require fields should be added.
         */
        private boolean canResolveFieldPathInSubgraph(String subgraph, String typeName, Path path) {
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
        private RequireFieldResolution resolveRequireField(
                String sourceSubgraph,
                String sourceTypeName,
                Path path,
                List<String> parentPath,
                Set<String> visitedSubgraphs) {

            // Check if directly resolvable in source subgraph
            if (canResolveFieldPathInSubgraph(sourceSubgraph, sourceTypeName, path)) {
                // Find an existing plan for the source subgraph, or create root plan
                SubgraphPlan sourcePlan = findExistingPlanForSubgraph(sourceSubgraph, parentPath);
                if (sourcePlan == null) {
                    sourcePlan = getOrCreateRootPlan(sourceSubgraph);
                }
                // Determine the parent path based on whether this is a lookup plan
                List<String> effectiveParentPath = parentPath; // adjustPath handles lookup prefix stripping
                // Pass sourceTypeName so we can track type changes and generate inline fragments
                // for fields on implementing types
                Map<String, String> responseKeys = addNestedFieldPathWithSubgraph(
                    sourcePlan, path, effectiveParentPath, FieldOrigin.ARTIFICIAL_REQUIRE, sourceSubgraph, sourceTypeName);
                return new RequireFieldResolution(sourcePlan, sourceSubgraph, responseKeys);
            }

            // Not in source - find a lookup path to a subgraph that has it
            Node sourceNode = new Node(sourceTypeName, sourceSubgraph);
            List<LookupMoveEdge> lookupEdges = graph.lookupEdgesFrom(sourceNode).toList();

            // FIRST PASS: Try to find a direct resolution (target subgraph can resolve the field)
            for (LookupMoveEdge lookupEdge : lookupEdges) {
                String targetSubgraph = lookupEdge.target().subgraph();
                String targetTypeName = lookupEdge.target().typeName();

                // Skip visited subgraphs (cycle prevention)
                if (visitedSubgraphs.contains(targetSubgraph)) {
                    continue;
                }

                // Check if the target subgraph can resolve the field directly
                if (canResolveFieldPathInSubgraph(targetSubgraph, targetTypeName, path)) {
                    RequireFieldResolution resolution = setupDirectLookupResolution(
                        sourceSubgraph, sourceTypeName, lookupEdge, path, parentPath, visitedSubgraphs);
                    if (resolution != null) {
                        return resolution;
                    }
                }
            }

            // SECOND PASS: No direct resolution found, try recursive multi-hop resolution
            // This handles cases like B->A->C where A is an intermediate hop
            for (LookupMoveEdge lookupEdge : lookupEdges) {
                String targetSubgraph = lookupEdge.target().subgraph();
                String targetTypeName = lookupEdge.target().typeName();

                // Skip visited subgraphs (cycle prevention)
                if (visitedSubgraphs.contains(targetSubgraph)) {
                    continue;
                }

                // Skip if target can resolve directly (already handled in first pass)
                if (canResolveFieldPathInSubgraph(targetSubgraph, targetTypeName, path)) {
                    continue;
                }

                // Try to resolve through this target
                Set<String> newVisited = new HashSet<>(visitedSubgraphs);
                newVisited.add(targetSubgraph);

                RequireFieldResolution resolution = resolveRequireField(
                    targetSubgraph, targetTypeName, path, parentPath, newVisited);

                if (resolution != null) {
                    // Found it through a chain! Need to set up the intermediate hop
                    // The resolution already created plans for the chain, but we need
                    // to ensure the lookup from source to target is set up

                    SubgraphPlan sourcePlan = findExistingPlanForSubgraph(sourceSubgraph, parentPath);
                    if (sourcePlan == null) {
                        sourcePlan = getOrCreateRootPlan(sourceSubgraph);
                    }

                    SubgraphPlan intermediatePlan = getOrCreateLookupPlan(lookupEdge, parentPath);

                    LookupPlanKey lookupPlanKey = new LookupPlanKey(lookupEdge, parentPath);
                    if (!processedLookupEdges.contains(lookupPlanKey)) {
                        processedLookupEdges.add(lookupPlanKey);

                        // Add key fields to source for the hop to target
                        List<String> lookupArgPath = parentPath; // adjustPath handles lookup prefix stripping
                        Map<String, Map<String, String>> lookupArgNestedResponseKeys = new HashMap<>();
                        for (LookupArgument lookupArg :lookupEdge.lookupArguments()) {
                            // Use addNestedFieldPath for nested paths like "compositeId.two"
                            Map<String, String> nestedResponseKeys = addNestedFieldPath(sourcePlan, lookupArg.path(), lookupArgPath, FieldOrigin.ARTIFICIAL_KEY);
                            lookupArgNestedResponseKeys.put(lookupArg.argumentName(), nestedResponseKeys);
                        }

                        // Add key field requirements to intermediate plan
                        for (LookupArgument lookupArg :lookupEdge.lookupArguments()) {
                            Map<String, String> nestedResponseKeys = lookupArgNestedResponseKeys.get(lookupArg.argumentName());
                            // Create path with response keys for nested fields like "compositeId.two"
                            Path keyPath = createNestedPath(lookupArg.path(), nestedResponseKeys);
                            intermediatePlan.addRequirement(lookupArg.argumentName(), new SelectedValue(keyPath), lookupArg.argumentType());
                        }
                    }

                    // Intermediate plan depends on source plan
                    addPlanDependency(intermediatePlan, sourcePlan);
                    // Resolution plan depends on intermediate plan
                    addPlanDependency(resolution.plan(), intermediatePlan);

                    return resolution;
                }
            }

            // Couldn't resolve the field
            return null;
        }

        /**
         * Finds an existing plan for a subgraph at the given response path.
         * Prefers the most specific lookup plan containing the path, then the root plan.
         * Returns null if no plan exists.
         */
        private SubgraphPlan findExistingPlanForSubgraph(String subgraph, List<String> parentPath) {
            SubgraphPlan rootPlan = null;
            SubgraphPlan bestLookupPlan = null;
            int bestLookupPathLength = -1;

            for (var entry : subgraphPlans.entrySet()) {
                SubgraphPlanKey key = entry.getKey();
                if (!key.subgraph().equals(subgraph)) {
                    continue;
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

        /**
         * Sets up a direct lookup resolution where the target subgraph can resolve the field.
         */
        private RequireFieldResolution setupDirectLookupResolution(
                String sourceSubgraph,
                String sourceTypeName,
                LookupMoveEdge lookupEdge,
                Path path,
                List<String> parentPath,
                Set<String> visitedSubgraphs) {

            // Get or create the lookup plan for this edge
            SubgraphPlan intermediatePlan = getOrCreateLookupPlan(lookupEdge, parentPath);

            // Find or create source plan
            SubgraphPlan sourcePlan = findExistingPlanForSubgraph(sourceSubgraph, parentPath);
            if (sourcePlan == null) {
                sourcePlan = getOrCreateRootPlan(sourceSubgraph);
            }

            // Add dependency: intermediate depends on source
            addPlanDependency(intermediatePlan, sourcePlan);

            LookupPlanKey lookupPlanKey = new LookupPlanKey(lookupEdge, parentPath);
            if (!processedLookupEdges.contains(lookupPlanKey)) {
                processedLookupEdges.add(lookupPlanKey);

                // Add key fields to source (at root for lookup plans, at parentPath for root plans)
                // Process ALL paths from all alternatives (e.g., <Book>.isbn | <Electronics>.sku)
                List<String> lookupArgPath = parentPath; // adjustPath handles lookup prefix stripping
                Map<String, Map<String, String>> lookupArgNestedResponseKeys = new HashMap<>();
                for (LookupArgument lookupArg :lookupEdge.lookupArguments()) {
                    Map<String, String> combinedResponseKeys = new LinkedHashMap<>();
                    for (Path altPath : lookupArg.extractPaths()) {
                        Map<String, String> nestedResponseKeys = addNestedFieldPath(sourcePlan, altPath, lookupArgPath, FieldOrigin.ARTIFICIAL_KEY);
                        combinedResponseKeys.putAll(nestedResponseKeys);
                    }
                    lookupArgNestedResponseKeys.put(lookupArg.argumentName(), combinedResponseKeys);
                }

                // Add key field requirements to intermediate plan
                for (LookupArgument lookupArg :lookupEdge.lookupArguments()) {
                    Map<String, String> nestedResponseKeys = lookupArgNestedResponseKeys.get(lookupArg.argumentName());
                    SelectedValue requirement = createRequirementSelectedValue(lookupArg, nestedResponseKeys);
                    intermediatePlan.addRequirement(lookupArg.argumentName(), requirement, lookupArg.argumentType());
                }
            }

            // Add the actual field to the intermediate plan (at root since it's a lookup)
            Map<String, String> responseKeys = addNestedFieldPath(intermediatePlan, path, List.of(), FieldOrigin.ARTIFICIAL_REQUIRE);

            // If this field has @require dependencies, resolve them
            // Only process requirements that belong to this specific field
            String fieldName = getLeafFieldName(path);
            if (lookupEdge.hasRequirements()) {
                String targetSubgraph = lookupEdge.target().subgraph();
                Set<String> newVisited = new HashSet<>(visitedSubgraphs);
                newVisited.add(targetSubgraph);

                Map<String, String> reqResponseKeys = new HashMap<>();
                for (var req : lookupEdge.requires()) {
                    // Only process requirements for the specific field being added
                    if (req.fieldName() == null || !req.fieldName().equals(fieldName)) {
                        continue;
                    }
                    for (Path reqPath : req.extractPaths()) {
                        RequireFieldResolution reqResolution = resolveRequireField(
                            sourceSubgraph, sourceTypeName, reqPath, parentPath, newVisited);
                        if (reqResolution != null) {
                            reqResponseKeys.putAll(reqResolution.fieldToResponseKey());

                            // Add dependency from intermediate to where the require was resolved
                            addPlanDependency(intermediatePlan, reqResolution.plan());
                        }
                    }

                    // Add the @require variable requirement to intermediate plan
                    SelectedValue transformedSelection = transformSelectionWithResponseKeys(
                        req.selection(), reqResponseKeys);
                    intermediatePlan.addRequirement(req.argumentName(), transformedSelection, req.argumentType());

                    // Add the field argument for this @require
                    List<String> fieldArgPath = List.of(fieldName);
                    intermediatePlan.addFieldArgument(fieldArgPath, req.argumentName(), req.argumentName());
                }
            }

            return new RequireFieldResolution(intermediatePlan, intermediatePlan.subgraph, responseKeys);
        }

        /**
         * Transforms a SelectedValue by replacing field names with their response keys.
         * This handles aliased fields where the response key differs from the field name.
         */
        private SelectedValue transformSelectionWithResponseKeys(SelectedValue selection,
                                                                  Map<String, String> responseKeys) {
            if (responseKeys == null || responseKeys.isEmpty()) {
                return selection;
            }

            List<Alternative> transformedAlts = new ArrayList<>();
            for (Alternative alt : selection.alternatives()) {
                transformedAlts.add(transformAlternativeWithResponseKeys(alt, responseKeys));
            }
            return new SelectedValue(transformedAlts);
        }

        private Alternative transformAlternativeWithResponseKeys(Alternative alt,
                                                                   Map<String, String> responseKeys) {
            return switch (alt) {
                case Path path -> transformPathWithResponseKeys(path, responseKeys);
                case ObjectSelection obj -> transformObjectSelectionWithResponseKeys(obj, responseKeys);
                case dev.feddi.federation.engine.parser.FieldSelectionMap.ListSelection list ->
                    transformListSelectionWithResponseKeys(list, responseKeys);
            };
        }

        private Path transformPathWithResponseKeys(Path path, Map<String, String> responseKeys) {
            List<dev.feddi.federation.engine.parser.FieldSelectionMap.PathSegment> transformedSegments = new ArrayList<>();
            for (var segment : path.segments()) {
                String fieldName = segment.fieldName();
                String responseKey = responseKeys.getOrDefault(fieldName, fieldName);
                transformedSegments.add(new dev.feddi.federation.engine.parser.FieldSelectionMap.PathSegment(
                    responseKey, segment.typeCondition()));
            }
            // Preserve the initial type condition
            return new Path(path.initialTypeCondition(), transformedSegments);
        }

        private ObjectSelection transformObjectSelectionWithResponseKeys(ObjectSelection obj,
                                                                           Map<String, String> responseKeys) {
            Path transformedPrefix = obj.pathPrefix() != null
                ? transformPathWithResponseKeys(obj.pathPrefix(), responseKeys)
                : null;

            List<dev.feddi.federation.engine.parser.FieldSelectionMap.ObjectField> transformedFields = new ArrayList<>();
            for (var field : obj.fields()) {
                SelectedValue transformedValue = transformSelectionWithResponseKeys(field.value(), responseKeys);
                transformedFields.add(new dev.feddi.federation.engine.parser.FieldSelectionMap.ObjectField(
                    field.name(), transformedValue));
            }
            return new ObjectSelection(transformedPrefix, transformedFields);
        }

        private dev.feddi.federation.engine.parser.FieldSelectionMap.ListSelection transformListSelectionWithResponseKeys(
                dev.feddi.federation.engine.parser.FieldSelectionMap.ListSelection list,
                Map<String, String> responseKeys) {
            Path transformedPrefix = list.pathPrefix() != null
                ? transformPathWithResponseKeys(list.pathPrefix(), responseKeys)
                : null;

            SelectedValue transformedElement = transformSelectionWithResponseKeys(list.elementValue(), responseKeys);
            return new dev.feddi.federation.engine.parser.FieldSelectionMap.ListSelection(
                transformedPrefix, transformedElement);
        }

        /**
         * Gets or creates an inline fragment context for adding fields.
         */
        InlineFragmentContext getInlineFragmentContext(String subgraph, List<String> parentPath,
                                                       String typeCondition, List<Directive> directives) {
            SubgraphPlan plan = getOrCreateRootPlan(subgraph);
            InlineFragmentNode fragmentNode = plan.getOrCreateInlineFragment(parentPath, typeCondition, directives);
            return new InlineFragmentContext(fragmentNode);
        }

        /**
         * Gets or creates an inline fragment context for a cross-subgraph lookup.
         * Sets up the lookup origin and creates the inline fragment in the target subgraph.
         */
        InlineFragmentContext getOrCreateInlineFragmentForLookup(OperationPath path, List<String> parentPath,
                                                                  String typeCondition, List<Directive> directives) {
            String subgraph = path.currentSubgraph();

            // Find the last lookup edge that enters this subgraph
            LookupMoveEdge enteringLookupEdge = null;
            for (Edge edge : path.getEdges()) {
                if (edge instanceof LookupMoveEdge lookupEdge
                    && lookupEdge.target().subgraph().equals(subgraph)) {
                    enteringLookupEdge = lookupEdge;
                }
            }

            // Get or create the appropriate plan
            SubgraphPlan plan;
            List<String> enteringEntryPath = parentPath;
            if (enteringLookupEdge != null) {
                enteringEntryPath = lookupEntryPath(path, enteringLookupEdge, parentPath);
                plan = getOrCreateLookupPlan(enteringLookupEdge, enteringEntryPath);
            } else {
                plan = getOrCreateRootPlan(subgraph);
            }

            // Add requirements for the lookup
            if (enteringLookupEdge != null) {
                // Find or create source plan
                String sourceSubgraph = enteringLookupEdge.source().subgraph();
                SubgraphPlan sourcePlan = findExistingPlanForSubgraph(sourceSubgraph, enteringEntryPath);
                if (sourcePlan == null) {
                    sourcePlan = getOrCreateRootPlan(sourceSubgraph);
                }

                // Track dependency
                addPlanDependency(plan, sourcePlan);

                // Add key fields to source subgraph (ARTIFICIAL_KEY)
                // For unions (which don't have fields), key fields must be added inside the inline fragment.
                // For interfaces and object types (which have fields), key fields can be at the parent level.
                LookupPlanKey enteringLookupPlanKey = new LookupPlanKey(enteringLookupEdge, enteringEntryPath);
                if (!processedLookupEdges.contains(enteringLookupPlanKey)) {
                    processedLookupEdges.add(enteringLookupPlanKey);
                    Map<Path, Map<String, String>> lookupArgNestedResponseKeys = new HashMap<>();

                    // Check if we need to add key fields inside an inline fragment.
                    // This is required when:
                    // 1. We're inside an inline fragment on a UNION type (unions can't have fields)
                    // 2. We're inside an inline fragment on an INTERFACE that doesn't define the key field
                    boolean needsInlineFragment = false;
                    if (typeCondition != null) {
                        // Check if the typeCondition is a member of a union.
                        Set<String> unionMemberships = graph.getUnionsForType(typeCondition);
                        if (!unionMemberships.isEmpty()) {
                            needsInlineFragment = true;
                        } else {
                            // Check if the typeCondition implements interfaces that don't have the key field.
                            // If so, we need to add the key field inside the inline fragment.
                            Set<String> interfaces = graph.getInterfacesForType(typeCondition);
                            if (!interfaces.isEmpty()) {
                                // Check if any interface can resolve all key fields
                                for (LookupArgument lookupArg :enteringLookupEdge.lookupArguments()) {
                                    boolean anyInterfaceHasField = false;
                                    Path keyPath = lookupArg.path();
                                    List<PathSegment> segments = keyPath.segments();
                                    String firstFieldName = segments.isEmpty() ? lookupArg.argumentName() : segments.get(0).fieldName();
                                    for (String iface : interfaces) {
                                        Node ifaceNode = new Node(iface, sourceSubgraph);
                                        boolean canResolve = graph.fieldEdgesFrom(ifaceNode)
                                            .anyMatch(e -> e.fieldName().equals(firstFieldName));
                                        if (canResolve) {
                                            anyInterfaceHasField = true;
                                            break;
                                        }
                                    }
                                    if (!anyInterfaceHasField) {
                                        needsInlineFragment = true;
                                        break;
                                    }
                                }
                            }
                        }
                    }

                    // Process ALL paths from all alternatives (e.g., <Book>.isbn | <Electronics>.sku)
                    Map<String, Map<String, String>> lookupArgCombinedResponseKeys = new HashMap<>();
                    List<String> lookupArgPath = enteringEntryPath; // adjustPath handles lookup prefix stripping
                    for (LookupArgument lookupArg :enteringLookupEdge.lookupArguments()) {
                        Map<String, String> combinedResponseKeys = new LinkedHashMap<>();

                        for (Path altPath : lookupArg.extractPaths()) {
                            Map<String, String> nestedResponseKeys;

                            // Determine the fragment type for this specific alternative path.
                            // For type-conditioned paths like <Book>.isbn, use "Book" as the fragment type.
                            // For paths without type conditions, fall back to the typeCondition parameter.
                            String altTypeCondition = altPath.hasInitialTypeCondition()
                                ? altPath.initialTypeCondition()
                                : typeCondition;
                            boolean altNeedsInlineFragment = needsInlineFragment;

                            // Re-evaluate needsInlineFragment for this alternative's type condition
                            // if it differs from the original typeCondition
                            if (altPath.hasInitialTypeCondition() && !altPath.initialTypeCondition().equals(typeCondition)) {
                                altNeedsInlineFragment = true; // type-conditioned paths always need inline fragments
                            }

                            if (altNeedsInlineFragment && altTypeCondition != null) {
                                // Add key field inside the correct inline fragment in the source plan
                                InlineFragmentNode sourceFragment = sourcePlan.getOrCreateInlineFragment(lookupArgPath, altTypeCondition, directives);
                                List<PathSegment> segments = altPath.segments();
                                List<String> fragPath = new ArrayList<>();
                                nestedResponseKeys = new LinkedHashMap<>();
                                for (int j = 0; j < segments.size(); j++) {
                                    String segFieldName = segments.get(j).fieldName();
                                    boolean hasChildrenSeg = (j < segments.size() - 1);
                                    sourceFragment.addFieldAtPath(null, segFieldName, fragPath, hasChildrenSeg, List.of(), List.of(), FieldOrigin.ARTIFICIAL_KEY);
                                    nestedResponseKeys.put(segFieldName, segFieldName);
                                    fragPath.add(segFieldName);
                                }
                            } else {
                                // Add key field at parent level (for interfaces and object types)
                                nestedResponseKeys = addNestedFieldPath(sourcePlan, altPath, lookupArgPath, FieldOrigin.ARTIFICIAL_KEY);
                            }
                            combinedResponseKeys.putAll(nestedResponseKeys);
                        }
                        lookupArgCombinedResponseKeys.put(lookupArg.argumentName(), combinedResponseKeys);
                    }

                    // Add key field requirements to target plan
                    for (LookupArgument lookupArg :enteringLookupEdge.lookupArguments()) {
                        Map<String, String> nestedResponseKeys = lookupArgCombinedResponseKeys.get(lookupArg.argumentName());
                        SelectedValue requirement = createRequirementSelectedValue(lookupArg, nestedResponseKeys);
                        plan.addRequirement(lookupArg.argumentName(), requirement, lookupArg.argumentType());
                    }
                }
            }

            // Create inline fragment context (path is adjusted for lookup entry in getOrCreateInlineFragment)
            InlineFragmentNode fragmentNode = plan.getOrCreateInlineFragment(parentPath, typeCondition, directives);
            return new InlineFragmentContext(fragmentNode);
        }

        /**
         * Records a field resolution for dependency tracking when the field is inside an inline fragment.
         * This handles lookups and key field requirements.
         */
        void recordFieldResolutionForFragment(OperationPath path, String fieldName,
                                              List<String> parentPath, boolean hasChildren,
                                              List<Argument> queryArguments, List<Directive> queryDirectives,
                                              InlineFragmentContext fragmentContext) {
            String subgraph = path.currentSubgraph();

            // Find the last lookup edge that enters the current subgraph
            LookupMoveEdge enteringLookupEdge = null;
            for (Edge edge : path.getEdges()) {
                if (edge instanceof LookupMoveEdge lookupEdge
                    && lookupEdge.target().subgraph().equals(subgraph)) {
                    enteringLookupEdge = lookupEdge;
                }
            }

            // Get or create the appropriate target plan
            SubgraphPlan targetPlan;
            List<String> enteringEntryPath = parentPath;
            if (enteringLookupEdge != null) {
                enteringEntryPath = lookupEntryPath(path, enteringLookupEdge, parentPath);
                targetPlan = getOrCreateLookupPlan(enteringLookupEdge, enteringEntryPath);
            } else {
                targetPlan = getOrCreateRootPlan(subgraph);
            }

            // Track dependencies from lookups (same as regular field resolution)
            for (Edge edge : path.getEdges()) {
                if (edge instanceof LookupMoveEdge lookupEdge) {
                    String sourceSubgraph = lookupEdge.source().subgraph();
                    List<String> edgeEntryPath = lookupEntryPath(path, lookupEdge, parentPath);

                    // Find or create source plan
                    SubgraphPlan sourcePlan = findExistingPlanForSubgraph(sourceSubgraph, edgeEntryPath);
                    if (sourcePlan == null) {
                        sourcePlan = getOrCreateRootPlan(sourceSubgraph);
                    }

                    // Get the lookup target plan
                    SubgraphPlan lookupTargetPlan = getOrCreateLookupPlan(lookupEdge, edgeEntryPath);

                    // Add dependency if this lookup edge enters the current subgraph
                    if (lookupEdge.target().subgraph().equals(subgraph)) {
                        addPlanDependency(lookupTargetPlan, sourcePlan);
                    }

                    // Handle key fields - ARTIFICIAL_KEY
                    // When we're in a fragment context (e.g., ... on Book inside a union),
                    // key fields must be added inside the fragment, not at the parent level.
                    // Process ALL paths from all alternatives (e.g., <Book>.isbn | <Electronics>.sku)
                    LookupPlanKey lookupPlanKey = new LookupPlanKey(lookupEdge, edgeEntryPath);
                    if (!processedLookupEdges.contains(lookupPlanKey)) {
                        processedLookupEdges.add(lookupPlanKey);

                        Map<String, Map<String, String>> lookupArgCombinedResponseKeys = new HashMap<>();
                        List<String> lookupArgPath = edgeEntryPath; // adjustPath handles lookup prefix stripping

                        for (LookupArgument lookupArg :lookupEdge.lookupArguments()) {
                            Map<String, String> combinedResponseKeys = new LinkedHashMap<>();

                            for (Path altPath : lookupArg.extractPaths()) {
                                Map<String, String> nestedResponseKeys;

                                // Use the path's initialTypeCondition to find the correct inline fragment,
                                // falling back to the lookup edge's source type
                                String typeCondForFragment = altPath.hasInitialTypeCondition()
                                    ? altPath.initialTypeCondition()
                                    : lookupEdge.source().typeName();

                                // Check if we have a fragment context in the SOURCE subgraph
                                // and if so, add key field to that fragment
                                InlineFragmentContext sourceFragmentContext =
                                    getInlineFragmentContext(sourceSubgraph, parentPath,
                                        typeCondForFragment, List.of());

                                if (sourceFragmentContext != null) {
                                    // Add key field inside the inline fragment
                                    // Handle nested paths by adding each segment
                                    List<PathSegment> segments = altPath.segments();
                                    nestedResponseKeys = new LinkedHashMap<>();
                                    for (int k = 0; k < segments.size(); k++) {
                                        String segFieldName = segments.get(k).fieldName();
                                        boolean hasChildrenSeg = (k < segments.size() - 1);
                                        sourceFragmentContext.addField(null, segFieldName, hasChildrenSeg, List.of(), List.of(), FieldOrigin.ARTIFICIAL_KEY);
                                        nestedResponseKeys.put(segFieldName, segFieldName);
                                        if (hasChildrenSeg) {
                                            sourceFragmentContext.enterField(segFieldName);
                                        }
                                    }
                                    // Exit all entered fields
                                    for (int k = 0; k < segments.size() - 1; k++) {
                                        sourceFragmentContext.exitField();
                                    }
                                } else {
                                    // Add key field at parent level (regular case for object types)
                                    // Use addNestedFieldPath for nested paths like "compositeId.two"
                                    nestedResponseKeys = addNestedFieldPath(sourcePlan, altPath, lookupArgPath, FieldOrigin.ARTIFICIAL_KEY);
                                }
                                combinedResponseKeys.putAll(nestedResponseKeys);
                            }
                            lookupArgCombinedResponseKeys.put(lookupArg.argumentName(), combinedResponseKeys);
                        }

                        // Add key field requirements to lookup target plan
                        for (LookupArgument lookupArg :lookupEdge.lookupArguments()) {
                            Map<String, String> nestedResponseKeys = lookupArgCombinedResponseKeys.get(lookupArg.argumentName());
                            SelectedValue requirement = createRequirementSelectedValue(lookupArg, nestedResponseKeys);
                            lookupTargetPlan.addRequirement(lookupArg.argumentName(), requirement, lookupArg.argumentType());
                        }
                    }

                    // Add @require fields - this handles cases where fields inside inline fragments
                    // have @require dependencies that need to be resolved
                    if (lookupEdge.hasRequirements() && lookupEdge.target().subgraph().equals(subgraph)) {
                        String sourceTypeName = lookupEdge.source().typeName();
                        Set<String> visitedForRequire = new HashSet<>();
                        visitedForRequire.add(subgraph); // Don't resolve @require in the target subgraph itself

                        // Get or create the response keys map for this lookup edge
                        Map<String, String> reqResponseKeys = requireFieldResponseKeys.computeIfAbsent(
                            new LookupPlanKey(lookupEdge, edgeEntryPath), k -> new HashMap<>());

                        Set<Path> resolvedPaths = new HashSet<>();
                        for (var req : lookupEdge.requires()) {
                            // Only resolve requirements for the current field being processed
                            if (req.fieldName() != null && !req.fieldName().equals(fieldName)) {
                                continue;
                            }
                            for (Path fieldPath : req.extractPaths()) {
                                // Skip if already resolved (another field might have triggered it)
                                if (resolvedPaths.contains(fieldPath)) {
                                    continue;
                                }
                                resolvedPaths.add(fieldPath);
                                // Use recursive resolution to find where to get this field
                                List<String> reqParentPath = parentPath; // adjustPath handles lookup prefix stripping
                                RequireFieldResolution resolution = resolveRequireField(
                                    sourceSubgraph, sourceTypeName, fieldPath, reqParentPath, visitedForRequire);

                                if (resolution != null) {
                                    reqResponseKeys.putAll(resolution.fieldToResponseKey());

                                    // Add dependency from target to where the require was resolved
                                    addPlanDependency(targetPlan, resolution.plan());
                                }
                            }
                        }

                        // Add @require field arguments
                        for (Requirement req : lookupEdge.requires()) {
                            if (req.fieldName() == null || req.fieldName().equals(fieldName)) {
                                List<String> adjustedFieldPath = new ArrayList<>();
                                if (targetPlan.lookupEntryPath != null) {
                                    if (parentPath.size() >= targetPlan.lookupEntryPath.size()) {
                                        adjustedFieldPath = new ArrayList<>(
                                            parentPath.subList(targetPlan.lookupEntryPath.size(), parentPath.size()));
                                    }
                                } else {
                                    adjustedFieldPath = new ArrayList<>(parentPath);
                                }
                                adjustedFieldPath.add(fieldName);
                                targetPlan.addFieldArgument(adjustedFieldPath, req.argumentName(), req.argumentName());
                            }
                        }

                        // Add @require variable requirements to target plan
                        for (Requirement req : lookupEdge.requires()) {
                            if (req.fieldName() == null || req.fieldName().equals(fieldName)) {
                                SelectedValue transformedSelection = transformSelectionWithResponseKeys(
                                    req.selection(), reqResponseKeys);
                                targetPlan.addRequirement(req.argumentName(), transformedSelection, req.argumentType());
                            }
                        }
                    }
                }
            }
        }

        /**
         * Builds the final execution plan.
         */
        ExecutionPlan buildPlan() {
            List<ExecutionStep> steps = new ArrayList<>();

            // Create execution steps (without parallelWith initially)
            for (SubgraphPlan plan : subgraphPlans.values()) {
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

                // A step needs repeated execution if it has requirements
                // (it needs to be executed for each entity from the parent step)
                boolean repeatedExecution = !stepRequirements.isEmpty();

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
                    requestedPaths
                );

                steps.add(step);
            }

            // Sort steps by dependencies (topological order)
            steps.sort(Comparator.comparingInt(ExecutionStep::id));

            // Compute parallelWith: steps with identical dependsOn can run in parallel
            List<ExecutionStep> stepsWithParallel = computeParallelWith(steps);

            return ExecutionPlan.of(stepsWithParallel);
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
                    step.requestedFieldPaths()
                ));
            }

            return result;
        }
    }
    
    /**
     * Helper class for building a subgraph's portion of the plan.
     * Maintains hierarchical field structure and builds an OperationDefinition.
     */
    private static class SubgraphPlan {
        final int id;
        final String subgraph;
        final Map<String, RequirementInfo> requirements = new LinkedHashMap<>();

        // Lookup origin tracking (for dependent subgraphs entered via @lookup)
        String lookupFieldName;                     // e.g., "productById"
        List<LookupArgument> lookupArguments;  // Key fields with argument info
        List<String> lookupEntryPath;               // The parentPath when we entered via lookup

        // Track field arguments from @require directives
        // Maps field path (e.g., ["shippingCost"]) to list of arguments for that field
        final Map<List<String>, List<FieldArgumentInfo>> fieldArguments = new HashMap<>();

        // Query-level variable definitions for pass-through
        private final List<VariableDefinition> queryVariableDefinitions;

        // The operation type (QUERY or MUTATION) for this plan
        private final OperationDefinition.Operation operationType;

        // Tree structure: path -> node info
        // Root level fields have empty path
        private final SelectionNode root = new SelectionNode("", true);

        /**
         * Info about a field argument from @require.
         */
        record FieldArgumentInfo(String argumentName, String variableName) {}

        SubgraphPlan(int id, String subgraph, List<VariableDefinition> queryVariableDefinitions,
                     OperationDefinition.Operation operationType) {
            this.id = id;
            this.subgraph = subgraph;
            this.queryVariableDefinitions = queryVariableDefinitions != null
                ? queryVariableDefinitions : List.of();
            this.operationType = operationType;
        }

        /**
         * Sets the lookup origin information for a subgraph entered via a @lookup edge.
         */
        void setLookupOrigin(LookupMoveEdge lookupEdge, List<String> entryPath) {
            this.lookupFieldName = lookupEdge.lookupField();
            this.lookupArguments = lookupEdge.lookupArguments();
            this.lookupEntryPath = new ArrayList<>(entryPath);
        }

        /**
         * Holds requirement information including the selection and type.
         */
        record RequirementInfo(SelectedValue selection, Type<?> type) {}
        /**
         * Adds an artificial field (for @key or @require) at the specified position.
         * Reuses existing field if already present, or generates unique alias if there's a clash.
         *
         * @return the response key where the value will be found (may be aliased)
         */
        String addArtificialField(String fieldName, List<String> parentPath, boolean hasChildren, FieldOrigin origin) {
            // Adjust parentPath if we entered via lookup
            List<String> adjustedPath = adjustPath(parentPath);

            SelectionNode parent = root;

            // Navigate to the parent node, creating intermediate nodes if needed.
            // Path segments are field names, but children are keyed by response keys (aliases).
            // When direct key lookup fails, search for a node with matching fieldName.
            for (String pathSegment : adjustedPath) {
                SelectionNode child = parent.children.get(pathSegment);
                if (child == null) {
                    // Not found by direct key - search children by field name
                    child = findLastChildByFieldName(parent, pathSegment);
                }
                if (child == null) {
                    // Still not found - create new node
                    child = new SelectionNode(pathSegment, true);
                    parent.children.put(pathSegment, child);
                }
                parent = child;
            }

            // Phase 1: Check if same field already exists (search by fieldName, not key)
            // Children are keyed by responseKey (alias), so we need to search by fieldName
            SelectionNode existingNode = findLastChildByFieldName(parent, fieldName);
            if (existingNode != null) {
                // Field already exists - reuse it, return its response key
                // Don't change origin - if it was REQUESTED, keep it REQUESTED
                return existingNode.responseKey();
            }

            // Phase 2: Check for response key clash
            String effectiveAlias = null;
            if (parent.hasChildWithResponseKey(fieldName)) {
                // Different field has same response key - generate unique alias
                effectiveAlias = generateUniqueAlias(fieldName, parent);
            }

            // Add the artificial field
            // Use the effective responseKey (alias if generated, otherwise fieldName) as the map key
            SelectionNode fieldNode = new SelectionNode(fieldName, hasChildren, origin);
            fieldNode.setAlias(effectiveAlias);
            String responseKey = fieldNode.responseKey();
            parent.children.put(responseKey, fieldNode);

            return responseKey;
        }

        /**
         * Generates a unique internal alias for an artificial field.
         * Format: __gql_{fieldName} or __gql_{fieldName}_{n} if that's taken
         */
        private String generateUniqueAlias(String fieldName, SelectionNode parent) {
            String base = "__gql_" + fieldName;
            String candidate = base;
            int counter = 0;
            while (parent.hasChildWithResponseKey(candidate)) {
                counter++;
                candidate = base + "_" + counter;
            }
            return candidate;
        }

        /**
         * Adjusts a parent path by stripping the lookup entry path prefix if applicable.
         */
        private List<String> adjustPath(List<String> parentPath) {
            if (lookupEntryPath != null && parentPath.size() >= lookupEntryPath.size()) {
                boolean prefixMatches = true;
                for (int i = 0; i < lookupEntryPath.size(); i++) {
                    if (!lookupEntryPath.get(i).equals(parentPath.get(i))) {
                        prefixMatches = false;
                        break;
                    }
                }
                if (prefixMatches) {
                    return parentPath.subList(lookupEntryPath.size(), parentPath.size());
                }
            }
            return parentPath;
        }

        /**
         * Adds a field with an alias at the specified hierarchical position.
         */
        void addFieldWithAlias(String alias, String fieldName, List<String> parentPath, boolean hasChildren,
                               List<Argument> queryArguments, List<Directive> queryDirectives) {
            addField(alias, fieldName, parentPath, hasChildren, queryArguments, queryDirectives, FieldOrigin.REQUESTED);
        }

        /**
         * Adds a field at the specified hierarchical position with origin tracking.
         */
        void addField(String fieldName, List<String> parentPath, boolean hasChildren,
                      List<Argument> queryArguments, List<Directive> queryDirectives, FieldOrigin origin) {
            addField(null, fieldName, parentPath, hasChildren, queryArguments, queryDirectives, origin);
        }

        /**
         * Adds a field at the specified hierarchical position with alias and origin tracking.
         */
        void addField(String alias, String fieldName, List<String> parentPath, boolean hasChildren,
                      List<Argument> queryArguments, List<Directive> queryDirectives, FieldOrigin origin) {
            // Adjust parentPath if we entered via lookup
            List<String> adjustedPath = parentPath;
            if (lookupEntryPath != null && parentPath.size() >= lookupEntryPath.size()) {
                // Verify the prefix matches before stripping
                boolean prefixMatches = true;
                for (int i = 0; i < lookupEntryPath.size(); i++) {
                    if (!lookupEntryPath.get(i).equals(parentPath.get(i))) {
                        prefixMatches = false;
                        break;
                    }
                }
                if (prefixMatches) {
                    adjustedPath = parentPath.subList(lookupEntryPath.size(), parentPath.size());
                }
            }

            SelectionNode parent = root;

            // Navigate to the parent node, creating intermediate nodes if needed.
            // Path segments are field names, but children are keyed by response keys (aliases).
            // When direct key lookup fails, search for a node with matching fieldName.
            for (String pathSegment : adjustedPath) {
                SelectionNode child = parent.children.get(pathSegment);
                if (child == null) {
                    // Not found by direct key - search children by field name
                    // Use the LAST matching node (most recently added) since we process in order
                    child = findLastChildByFieldName(parent, pathSegment);
                }
                if (child == null) {
                    // Still not found - create new node
                    child = new SelectionNode(pathSegment, true);
                    parent.children.put(pathSegment, child);
                }
                parent = child;
            }

            // Add the field as a child of the parent, with query arguments and directives.
            // We use the response key (alias if present, otherwise field name) as the map key
            // to ensure fields with different aliases are kept separate, even if they reference
            // the same underlying field name with different arguments.
            String responseKey = alias != null ? alias : fieldName;

            SelectionNode existingNode = parent.children.get(responseKey);
            if (existingNode != null) {
                // Node with same response key exists - update origin and arguments
                existingNode.updateOrigin(origin);
                existingNode.setQueryArguments(queryArguments);
                existingNode.setQueryDirectives(queryDirectives);
                // Set alias if provided and not already set
                if (alias != null && existingNode.alias == null) {
                    existingNode.setAlias(alias);
                }
            } else {
                // Create new node with specified origin
                SelectionNode fieldNode = new SelectionNode(fieldName, hasChildren, origin);
                fieldNode.setAlias(alias);
                fieldNode.setQueryArguments(queryArguments);
                fieldNode.setQueryDirectives(queryDirectives);
                parent.children.put(responseKey, fieldNode);
            }
        }

        /**
         * Finds the LAST child node by its field name, searching through all children.
         * This is needed because children are keyed by response key (alias) but paths use field names.
         * Returns the most recently added matching node, which is important when there are
         * multiple aliases for the same field - we want the one currently being processed.
         */
        private SelectionNode findLastChildByFieldName(SelectionNode parent, String fieldName) {
            SelectionNode lastMatch = null;
            for (SelectionNode child : parent.children.values()) {
                if (child.fieldName.equals(fieldName)) {
                    lastMatch = child;
                }
            }
            return lastMatch;
        }

        void addRequirement(String name, SelectedValue selection, Type<?> type) {
            requirements.putIfAbsent(name, new RequirementInfo(selection, type));
        }

        /**
         * Collects all artificial field paths from the selection tree.
         * Returns dot-notation paths like "orders.customerId" for fields that were
         * added for internal purposes (not requested by the client).
         * Paths are prefixed with lookupEntryPath if this is a lookup step.
         */
        Set<String> collectArtificialFieldPaths() {
            Set<String> paths = new HashSet<>();
            // Start with the lookup prefix if this is a lookup step
            String prefix = buildLookupPrefix();
            collectFieldPathsRecursive(root, prefix, paths, false);
            return paths;
        }

        /**
         * Collects all requested field paths from the selection tree.
         * Returns dot-notation paths for fields that were explicitly requested by the client.
         * Paths are prefixed with lookupEntryPath if this is a lookup step.
         */
        Set<String> collectRequestedFieldPaths() {
            Set<String> paths = new HashSet<>();
            // Start with the lookup prefix if this is a lookup step
            String prefix = buildLookupPrefix();
            collectFieldPathsRecursive(root, prefix, paths, true);
            return paths;
        }

        /**
         * Builds the path prefix from lookupEntryPath.
         * For lookup steps, fields are stored without the lookup prefix, so we need
         * to add it back when collecting paths to match the response structure.
         */
        private String buildLookupPrefix() {
            if (lookupEntryPath == null || lookupEntryPath.isEmpty()) {
                return "";
            }
            return String.join(".", lookupEntryPath);
        }

        private void collectFieldPathsRecursive(SelectionNode node, String currentPath, Set<String> paths, boolean collectRequested) {
            for (SelectionNode child : node.children.values()) {
                // Use responseKey (alias if present) since that's what appears in the response
                String responseKey = child.responseKey();
                String fieldPath = currentPath.isEmpty() ? responseKey : currentPath + "." + responseKey;
                if (collectRequested) {
                    if (child.origin == FieldOrigin.REQUESTED) {
                        paths.add(fieldPath);
                    }
                } else {
                    if (child.origin != FieldOrigin.REQUESTED) {
                        paths.add(fieldPath);
                    }
                }
                collectFieldPathsRecursive(child, fieldPath, paths, collectRequested);
            }
            // Also check inline fragments
            for (InlineFragmentNode fragment : node.inlineFragments) {
                collectFieldPathsRecursive(fragment.root, currentPath, paths, collectRequested);
            }
        }

        /**
         * Adds an opaque field that should be rendered with its full selection tree.
         * Used for introspection fields (__schema, __type) where the entire selection
         * is passed to graphql-java for execution without further planning.
         */
        void addOpaqueField(FieldSelection selection, List<String> parentPath) {
            SelectionNode parent = root;

            // Navigate to the parent node, creating intermediate nodes if needed.
            // Path segments are field names, but children are keyed by response keys (aliases).
            // When direct key lookup fails, search for a node with matching fieldName.
            for (String pathSegment : parentPath) {
                SelectionNode child = parent.children.get(pathSegment);
                if (child == null) {
                    // Not found by direct key - search children by field name
                    child = findLastChildByFieldName(parent, pathSegment);
                }
                if (child == null) {
                    // Still not found - create new node
                    child = new SelectionNode(pathSegment, true);
                    parent.children.put(pathSegment, child);
                }
                parent = child;
            }

            // Add the field as an opaque node
            SelectionNode fieldNode = parent.children.computeIfAbsent(selection.fieldName(),
                name -> new SelectionNode(name, selection.hasSubSelections()));
            fieldNode.setOpaqueSelection(selection);
            fieldNode.setQueryArguments(selection.arguments());
            fieldNode.setQueryDirectives(selection.directives());
        }

        /**
         * Adds an argument that should be passed to a field when building the operation.
         */
        void addFieldArgument(List<String> fieldPath, String argumentName, String variableName) {
            List<FieldArgumentInfo> args = fieldArguments.computeIfAbsent(new ArrayList<>(fieldPath), k -> new ArrayList<>());
            // Deduplicate: don't add if same argument already exists
            for (FieldArgumentInfo existing : args) {
                if (existing.argumentName().equals(argumentName)) {
                    return;
                }
            }
            args.add(new FieldArgumentInfo(argumentName, variableName));
        }

        /**
         * Gets or creates an inline fragment at the specified position.
         */
        InlineFragmentNode getOrCreateInlineFragment(List<String> parentPath, String typeCondition, List<Directive> directives) {
            // Adjust parentPath if we entered via lookup
            List<String> adjustedPath = parentPath;
            if (lookupEntryPath != null && parentPath.size() >= lookupEntryPath.size()) {
                boolean prefixMatches = true;
                for (int i = 0; i < lookupEntryPath.size(); i++) {
                    if (!lookupEntryPath.get(i).equals(parentPath.get(i))) {
                        prefixMatches = false;
                        break;
                    }
                }
                if (prefixMatches) {
                    adjustedPath = parentPath.subList(lookupEntryPath.size(), parentPath.size());
                }
            }

            SelectionNode parent = root;

            // Navigate to the parent node.
            // Path segments are field names, but children are keyed by response keys (aliases).
            // When direct key lookup fails, search for a node with matching fieldName.
            for (String pathSegment : adjustedPath) {
                SelectionNode child = parent.children.get(pathSegment);
                if (child == null) {
                    // Not found by direct key - search children by field name
                    child = findLastChildByFieldName(parent, pathSegment);
                }
                if (child == null) {
                    // Still not found - create new node
                    child = new SelectionNode(pathSegment, true);
                    parent.children.put(pathSegment, child);
                }
                parent = child;
            }

            // Get or create the inline fragment
            return parent.getOrCreateInlineFragment(typeCondition, directives);
        }

        /**
         * Builds an OperationDefinition from the collected field structure.
         * Includes variable definitions for each requirement.
         * If this subgraph was entered via lookup, wraps the selection set with the lookup field.
         */
        OperationDefinition buildOperation() {
            SelectionSet innerSelectionSet = buildSelectionSet(root);
            SelectionSet finalSelectionSet;

            if (lookupFieldName != null && lookupArguments != null) {
                // Build lookup field with arguments
                List<Argument> args = new ArrayList<>();
                for (LookupArgument lookupArg :lookupArguments) {
                    args.add(Argument.newArgument()
                        .name(lookupArg.argumentName())
                        .value(VariableReference.newVariableReference()
                            .name(lookupArg.argumentName())
                            .build())
                        .build());
                }

                Field lookupField = Field.newField()
                    .name(lookupFieldName)
                    .arguments(args)
                    .selectionSet(innerSelectionSet)
                    .build();

                finalSelectionSet = SelectionSet.newSelectionSet()
                    .selections(List.of(lookupField))
                    .build();
            } else {
                finalSelectionSet = innerSelectionSet;
            }

            // Build variable definitions from requirements
            List<VariableDefinition> variableDefinitions = new ArrayList<>();
            Set<String> definedVarNames = new HashSet<>();

            for (var entry : requirements.entrySet()) {
                Type<?> varType = entry.getValue().type();
                if (varType != null) {
                    VariableDefinition varDef = VariableDefinition.newVariableDefinition()
                        .name(entry.getKey())
                        .type(varType)
                        .build();
                    variableDefinitions.add(varDef);
                    definedVarNames.add(entry.getKey());
                }
            }

            // Add query variable definitions for variables used in field arguments
            Set<String> usedQueryVars = collectUsedQueryVariables(root);
            for (VariableDefinition queryVarDef : queryVariableDefinitions) {
                if (usedQueryVars.contains(queryVarDef.getName()) && !definedVarNames.contains(queryVarDef.getName())) {
                    variableDefinitions.add(queryVarDef);
                    definedVarNames.add(queryVarDef.getName());
                }
            }

            // Lookup steps are always queries, even if the original operation was a mutation
            // because @lookup fields are defined on Query type
            OperationDefinition.Operation effectiveOperationType =
                (lookupFieldName != null) ? OperationDefinition.Operation.QUERY : operationType;

            var builder = OperationDefinition.newOperationDefinition()
                .operation(effectiveOperationType)
                .selectionSet(finalSelectionSet);

            if (!variableDefinitions.isEmpty()) {
                builder.variableDefinitions(variableDefinitions);
            }

            return builder.build();
        }

        /**
         * Collects all query variable names referenced in field arguments and directives.
         */
        private Set<String> collectUsedQueryVariables(SelectionNode node) {
            Set<String> usedVars = new HashSet<>();
            collectUsedQueryVariablesRecursive(node, usedVars);
            return usedVars;
        }

        private void collectUsedQueryVariablesRecursive(SelectionNode node, Set<String> usedVars) {
            // Check this node's query arguments for variable references
            for (Argument arg : node.queryArguments) {
                collectVariableReferencesFromValue(arg.getValue(), usedVars);
            }
            // Check this node's directives for variable references (e.g., @skip(if: $var))
            for (Directive directive : node.queryDirectives) {
                for (Argument arg : directive.getArguments()) {
                    collectVariableReferencesFromValue(arg.getValue(), usedVars);
                }
            }
            // Recurse into children
            for (SelectionNode child : node.children.values()) {
                collectUsedQueryVariablesRecursive(child, usedVars);
            }
        }

        private void collectVariableReferencesFromValue(graphql.language.Value<?> value, Set<String> usedVars) {
            if (value instanceof VariableReference varRef) {
                usedVars.add(varRef.getName());
            } else if (value instanceof graphql.language.ArrayValue arrayValue) {
                for (graphql.language.Value<?> element : arrayValue.getValues()) {
                    collectVariableReferencesFromValue(element, usedVars);
                }
            } else if (value instanceof graphql.language.ObjectValue objectValue) {
                for (graphql.language.ObjectField field : objectValue.getObjectFields()) {
                    collectVariableReferencesFromValue(field.getValue(), usedVars);
                }
            }
        }
        
        /**
         * Recursively builds a SelectionSet from the tree structure.
         */
        private SelectionSet buildSelectionSet(SelectionNode node) {
            return buildSelectionSet(node, new ArrayList<>());
        }

        /**
         * Recursively builds a SelectionSet from the tree structure, tracking path for arguments.
         */
        private SelectionSet buildSelectionSet(SelectionNode node, List<String> currentPath) {
            if (node.children.isEmpty() && node.inlineFragments.isEmpty()) {
                return null;
            }

            List<graphql.language.Selection<?>> selections = new ArrayList<>();

            // Build field selections
            for (SelectionNode child : node.children.values()) {
                // Build the path to this field for argument lookup
                List<String> childPath = new ArrayList<>(currentPath);
                childPath.add(child.fieldName);

                Field.Builder fieldBuilder = Field.newField()
                    .name(child.fieldName)
                    .alias(child.alias);

                // Combine query arguments (from original query) with @require arguments
                List<Argument> allArguments = new ArrayList<>();

                // Add query arguments first (from the original query)
                if (!child.queryArguments.isEmpty()) {
                    allArguments.addAll(child.queryArguments);
                }

                // Add @require arguments
                List<FieldArgumentInfo> requireArgs = fieldArguments.get(childPath);
                if (requireArgs != null && !requireArgs.isEmpty()) {
                    for (FieldArgumentInfo arg : requireArgs) {
                        allArguments.add(Argument.newArgument()
                            .name(arg.argumentName())
                            .value(VariableReference.newVariableReference()
                                .name(arg.variableName())
                                .build())
                            .build());
                    }
                }

                if (!allArguments.isEmpty()) {
                    fieldBuilder.arguments(allArguments);
                }

                // Add directives (e.g., @skip, @include with variable conditions)
                if (!child.queryDirectives.isEmpty()) {
                    fieldBuilder.directives(child.queryDirectives);
                }

                // For opaque fields, build selection set from the original selection tree
                SelectionSet childSelectionSet;
                if (child.isOpaque() && child.opaqueSelection.hasSubSelections()) {
                    childSelectionSet = buildOpaqueSelectionSet(child.opaqueSelection.subSelections());
                } else {
                    childSelectionSet = buildSelectionSet(child, childPath);
                }
                if (childSelectionSet != null) {
                    fieldBuilder.selectionSet(childSelectionSet);
                }

                selections.add(fieldBuilder.build());
            }

            // Build inline fragment selections
            for (InlineFragmentNode fragment : node.inlineFragments) {
                InlineFragment.Builder fragmentBuilder = InlineFragment.newInlineFragment();

                if (fragment.typeCondition != null) {
                    fragmentBuilder.typeCondition(TypeName.newTypeName(fragment.typeCondition).build());
                }

                if (!fragment.directives.isEmpty()) {
                    fragmentBuilder.directives(fragment.directives);
                }

                // Build the selection set for fields inside this inline fragment
                SelectionSet fragmentSelectionSet = buildSelectionSet(fragment.root, currentPath);
                if (fragmentSelectionSet != null) {
                    fragmentBuilder.selectionSet(fragmentSelectionSet);
                }

                selections.add(fragmentBuilder.build());
            }

            return SelectionSet.newSelectionSet()
                .selections(selections)
                .build();
        }

        /**
         * Builds a SelectionSet from opaque selections (used for introspection fields).
         * Converts our Selection model back to graphql-java AST.
         */
        private SelectionSet buildOpaqueSelectionSet(List<Selection> selections) {
            if (selections.isEmpty()) {
                return null;
            }

            List<graphql.language.Selection<?>> astSelections = new ArrayList<>();

            for (Selection selection : selections) {
                if (selection instanceof FieldSelection fieldSel) {
                    Field.Builder fieldBuilder = Field.newField()
                        .name(fieldSel.fieldName())
                        .alias(fieldSel.alias());

                    if (!fieldSel.arguments().isEmpty()) {
                        fieldBuilder.arguments(fieldSel.arguments());
                    }

                    if (!fieldSel.directives().isEmpty()) {
                        fieldBuilder.directives(fieldSel.directives());
                    }

                    if (fieldSel.hasSubSelections()) {
                        SelectionSet subSet = buildOpaqueSelectionSet(fieldSel.subSelections());
                        if (subSet != null) {
                            fieldBuilder.selectionSet(subSet);
                        }
                    }

                    astSelections.add(fieldBuilder.build());
                } else if (selection instanceof InlineFragmentSelection inlineSel) {
                    InlineFragment.Builder fragmentBuilder = InlineFragment.newInlineFragment();

                    if (inlineSel.typeCondition() != null) {
                        fragmentBuilder.typeCondition(TypeName.newTypeName(inlineSel.typeCondition()).build());
                    }

                    if (!inlineSel.directives().isEmpty()) {
                        fragmentBuilder.directives(inlineSel.directives());
                    }

                    if (!inlineSel.subSelections().isEmpty()) {
                        SelectionSet subSet = buildOpaqueSelectionSet(inlineSel.subSelections());
                        if (subSet != null) {
                            fragmentBuilder.selectionSet(subSet);
                        }
                    }

                    astSelections.add(fragmentBuilder.build());
                }
            }

            return SelectionSet.newSelectionSet()
                .selections(astSelections)
                .build();
        }
    }

    /**
     * Origin of a field in the selection tree.
     * Used to track whether a field was explicitly requested by the client
     * or added internally for lookups/@require dependencies.
     */
    public enum FieldOrigin {
        /** Field was explicitly requested by the client query */
        REQUESTED,
        /** Field was added for @key/@is lookup requirements */
        ARTIFICIAL_KEY,
        /** Field was added for @require directive dependencies */
        ARTIFICIAL_REQUIRE
    }

    /**
     * Node in the selection tree being built.
     */
    private static class SelectionNode {
        final String fieldName;
        final boolean hasChildren;
        final Map<String, SelectionNode> children = new LinkedHashMap<>();
        final List<InlineFragmentNode> inlineFragments = new ArrayList<>();
        String alias;  // The alias for this field (null if no alias)
        List<Argument> queryArguments = List.of();
        List<Directive> queryDirectives = List.of();
        FieldOrigin origin = FieldOrigin.REQUESTED;  // Default to requested

        // For opaque fields (like introspection), stores the original selection with all sub-selections
        FieldSelection opaqueSelection;

        SelectionNode(String fieldName, boolean hasChildren) {
            this.fieldName = fieldName;
            this.hasChildren = hasChildren;
        }

        SelectionNode(String fieldName, boolean hasChildren, FieldOrigin origin) {
            this.fieldName = fieldName;
            this.hasChildren = hasChildren;
            this.origin = origin;
        }

        /**
         * Returns the response key for this field (alias if present, otherwise field name).
         */
        String responseKey() {
            return alias != null ? alias : fieldName;
        }

        void setAlias(String alias) {
            this.alias = alias;
        }

        /**
         * Updates the origin, but REQUESTED always wins over ARTIFICIAL.
         * This handles the case where a field is both requested and needed internally.
         */
        void updateOrigin(FieldOrigin newOrigin) {
            // REQUESTED wins - if field was requested, keep it as requested
            if (this.origin == FieldOrigin.REQUESTED) {
                return;
            }
            this.origin = newOrigin;
        }

        void setQueryArguments(List<Argument> arguments) {
            if (arguments != null && !arguments.isEmpty()) {
                this.queryArguments = arguments;
            }
        }

        void setQueryDirectives(List<Directive> directives) {
            if (directives != null && !directives.isEmpty()) {
                this.queryDirectives = directives;
            }
        }

        void setOpaqueSelection(FieldSelection selection) {
            this.opaqueSelection = selection;
        }

        boolean isOpaque() {
            return opaqueSelection != null;
        }

        /**
         * Checks if a child with the given response key already exists.
         * Used to detect naming clashes when adding artificial fields.
         */
        boolean hasChildWithResponseKey(String responseKey) {
            return children.values().stream()
                .anyMatch(child -> responseKey.equals(child.responseKey()));
        }

        /**
         * Gets or creates an inline fragment node with the given type condition.
         */
        InlineFragmentNode getOrCreateInlineFragment(String typeCondition, List<Directive> directives) {
            // Look for existing inline fragment with same type condition
            for (InlineFragmentNode fragment : inlineFragments) {
                if ((typeCondition == null && fragment.typeCondition == null) ||
                    (typeCondition != null && typeCondition.equals(fragment.typeCondition))) {
                    return fragment;
                }
            }
            // Create new inline fragment
            InlineFragmentNode fragment = new InlineFragmentNode(typeCondition, directives);
            inlineFragments.add(fragment);
            return fragment;
        }
    }

    /**
     * Node representing an inline fragment in the selection tree.
     */
    private static class InlineFragmentNode {
        final String typeCondition;
        final List<Directive> directives;
        final SelectionNode root = new SelectionNode("", true);

        InlineFragmentNode(String typeCondition, List<Directive> directives) {
            this.typeCondition = typeCondition;
            this.directives = directives != null ? directives : List.of();
        }

        /**
         * Adds a field to this inline fragment's selections.
         */
        void addField(String alias, String fieldName, boolean hasChildren, List<Argument> arguments, List<Directive> fieldDirectives) {
            addFieldAtPath(alias, fieldName, List.of(), hasChildren, arguments, fieldDirectives);
        }

        /**
         * Adds a field at a specific path within this inline fragment's selection tree.
         * This supports nested fields like: ... on Article { suggestedArticles { id } }
         * where "id" needs to be added under "suggestedArticles".
         */
        void addFieldAtPath(String alias, String fieldName, List<String> path, boolean hasChildren,
                            List<Argument> arguments, List<Directive> fieldDirectives) {
            addFieldAtPath(alias, fieldName, path, hasChildren, arguments, fieldDirectives, FieldOrigin.REQUESTED);
        }

        /**
         * Adds a field at a specific path within this inline fragment's selection tree.
         * This supports nested fields like: ... on Article { suggestedArticles { id } }
         * where "id" needs to be added under "suggestedArticles".
         *
         * @param origin the origin of the field (REQUESTED, ARTIFICIAL_KEY, ARTIFICIAL_REQUIRE)
         */
        void addFieldAtPath(String alias, String fieldName, List<String> path, boolean hasChildren,
                            List<Argument> arguments, List<Directive> fieldDirectives, FieldOrigin origin) {
            SelectionNode parent = root;

            // Navigate to the parent node.
            // Path segments are field names, but children are keyed by response keys (aliases).
            for (String segment : path) {
                SelectionNode child = parent.children.get(segment);
                if (child == null) {
                    // Not found by direct key - search children by field name
                    child = findLastChildByFieldName(parent, segment);
                }
                if (child == null) {
                    throw new PlanningException("Parent node not found in path " + path +
                        " at segment '" + segment + "' while adding field '" + fieldName + "'");
                }
                parent = child;
            }

            // Add the field as a child of the parent.
            // Use response key (alias if present, otherwise field name) to uniquely identify
            // the field in the selection set. This ensures that multiple aliases of the same
            // field with different arguments are kept separate.
            String responseKey = alias != null ? alias : fieldName;
            SelectionNode fieldNode = parent.children.get(responseKey);
            if (fieldNode == null) {
                fieldNode = new SelectionNode(fieldName, hasChildren, origin);
                parent.children.put(responseKey, fieldNode);
            }
            // Only update origin if field was just created or is being promoted from REQUESTED
            // Note: updateOrigin respects REQUESTED > ARTIFICIAL precedence
            fieldNode.updateOrigin(origin);
            // Only set alias/arguments/directives on newly created nodes
            // (computeIfAbsent creates with passed origin, so we always set on new nodes)
            if (alias != null && fieldNode.alias == null) {
                fieldNode.setAlias(alias);
            }
            if (arguments != null && !arguments.isEmpty() && fieldNode.queryArguments.isEmpty()) {
                fieldNode.setQueryArguments(arguments);
            }
            if (fieldDirectives != null && !fieldDirectives.isEmpty() && fieldNode.queryDirectives.isEmpty()) {
                fieldNode.setQueryDirectives(fieldDirectives);
            }
        }

        /**
         * Finds the LAST child node by its field name.
         */
        private SelectionNode findLastChildByFieldName(SelectionNode parent, String fieldName) {
            SelectionNode lastMatch = null;
            for (SelectionNode child : parent.children.values()) {
                if (child.fieldName.equals(fieldName)) {
                    lastMatch = child;
                }
            }
            return lastMatch;
        }
    }

    /**
     * Context for tracking fields being added to an inline fragment.
     * Tracks the current path within the fragment for nested field support.
     */
    private static class InlineFragmentContext {
        final InlineFragmentNode fragmentNode;
        final List<String> currentPath = new ArrayList<>();  // Path within the fragment

        InlineFragmentContext(InlineFragmentNode fragmentNode) {
            this.fragmentNode = fragmentNode;
        }

        void addField(String alias, String fieldName, boolean hasChildren, List<Argument> arguments, List<Directive> directives) {
            fragmentNode.addFieldAtPath(alias, fieldName, currentPath, hasChildren, arguments, directives);
        }

        void addField(String alias, String fieldName, boolean hasChildren, List<Argument> arguments, List<Directive> directives, FieldOrigin origin) {
            fragmentNode.addFieldAtPath(alias, fieldName, currentPath, hasChildren, arguments, directives, origin);
        }

        void enterField(String fieldName) {
            currentPath.add(fieldName);
        }

        void exitField() {
            if (!currentPath.isEmpty()) {
                currentPath.remove(currentPath.size() - 1);
            }
        }
    }
    
    /**
     * Exception thrown when planning fails.
     */
    public static class PlanningException extends RuntimeException {
        public PlanningException(String message) {
            super(message);
        }
        
        public PlanningException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
