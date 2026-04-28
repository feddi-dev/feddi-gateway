package dev.feddi.federation.engine.planner;

import dev.feddi.federation.engine.Constants;
import dev.feddi.federation.engine.IntrospectionFields;
import dev.feddi.federation.engine.graph.Edge;
import dev.feddi.federation.engine.graph.FieldMoveEdge;
import dev.feddi.federation.engine.graph.Graph;
import dev.feddi.federation.engine.graph.LookupMoveEdge;
import dev.feddi.federation.engine.graph.Node;
import dev.feddi.federation.engine.parser.FieldSelectionMap.Path;
import dev.feddi.federation.engine.parser.FieldSelectionMap.PathSegment;
import dev.feddi.federation.engine.parser.FieldSelectionMap.SelectedValue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Core pathfinding algorithm for query planning.
 * Resolves fields through direct paths first, then through lookup-based paths when needed.
 */
public final class PathFinder {
    
    private final Graph graph;
    
    public PathFinder(Graph graph) {
        this.graph = graph;
    }
    
    /**
     * Finds all paths to resolve a field from the current position.
     * First tries direct paths, then indirect paths via lookups.
     *
     * @param currentPath the current path (position in the graph)
     * @param fieldName the field to resolve
     * @return list of paths that can resolve the field
     */
    public List<OperationPath> findPaths(OperationPath currentPath, String fieldName) {
        // First, try direct paths (field in same subgraph)
        List<OperationPath> directPaths = findDirectPaths(currentPath, fieldName);
        
        if (!directPaths.isEmpty()) {
            // Found direct resolution, return best direct paths
            return BestPathTracker.findBestPaths(directPaths);
        }
        
        // No direct path, try indirect paths via lookups
        List<OperationPath> indirectPaths = findIndirectPaths(
            currentPath, 
            fieldName, 
            currentPath.copyVisitedSubgraphs()
        );
        
        return BestPathTracker.findBestPaths(indirectPaths);
    }
    
    /**
     * Finds direct paths to a field (field can be resolved in current/reachable subgraph).
     *
     * @param currentPath the current path
     * @param fieldName the field to find
     * @return list of direct paths
     */
    public List<OperationPath> findDirectPaths(OperationPath currentPath, String fieldName) {
        List<OperationPath> result = new ArrayList<>();
        Node currentNode = currentPath.tail();

        // __typename is always resolvable at zero cost from any position
        // It doesn't require a graph edge - every GraphQL type has __typename
        if (IntrospectionFields.TYPENAME.equals(fieldName)) {
            // Special case: if we're at the $root subgraph, we need to route to an actual subgraph
            // Find any outgoing edge to get a real subgraph node
            if ("$root".equals(currentNode.subgraph())) {
                var anyEdge = graph.fieldEdgesFrom(currentNode).findFirst();
                if (anyEdge.isPresent()) {
                    Node targetNode = anyEdge.get().target();
                    // Create edge to the target subgraph's root node of same type
                    Node subgraphRoot = new Node(currentNode.typeName(), targetNode.subgraph());
                    FieldMoveEdge typenameEdge = new FieldMoveEdge(
                        IntrospectionFields.TYPENAME, currentNode, subgraphRoot, 0
                    );
                    result.add(currentPath.advance(typenameEdge));
                    return result;
                }
            }
            // Normal case: stay at current node
            FieldMoveEdge typenameEdge = new FieldMoveEdge(
                IntrospectionFields.TYPENAME, currentNode, currentNode, 0
            );
            result.add(currentPath.advance(typenameEdge));
            return result;
        }

        // __schema and __type are introspection fields resolved by the engine itself
        // They are only valid at the Query root and route to the virtual $introspection subgraph
        if (IntrospectionFields.SCHEMA.equals(fieldName) || IntrospectionFields.TYPE.equals(fieldName)) {
            if ("$root".equals(currentNode.subgraph()) && Constants.QUERY.equals(currentNode.typeName())) {
                // Route to the virtual $introspection subgraph
                Node introspectionNode = new Node(Constants.QUERY, "$introspection");
                FieldMoveEdge edge = new FieldMoveEdge(
                    fieldName, currentNode, introspectionNode, 0
                );
                result.add(currentPath.advance(edge));
                return result;
            }
            // __schema/__type at non-root position is invalid - return empty to trigger error
            return result;
        }

        // Look for FieldMoveEdges that match the field name from the current node
        graph.fieldEdgesFrom(currentNode)
            .filter(edge -> edge.fieldName().equals(fieldName))
            .filter(edge -> canAccessField(currentPath, edge))
            .forEach(edge -> {
                OperationPath newPath = currentPath.advance(edge);
                result.add(newPath);
            });

        // If type context is set (inside inline fragment), also search from the narrowed type
        // e.g., if at Content/content with typeContext="Article", also search Article/content
        String typeContext = currentPath.typeContext();
        if (typeContext != null && result.isEmpty()) {
            Node narrowedNode = new Node(typeContext, currentNode.subgraph());
            graph.fieldEdgesFrom(narrowedNode)
                .filter(edge -> edge.fieldName().equals(fieldName))
                .filter(edge -> canAccessField(currentPath, edge))
                .forEach(edge -> {
                    OperationPath newPath = currentPath.advance(edge);
                    result.add(newPath);
                });
        }

        // Also check for fields provided via @provides through the current path
        if (result.isEmpty()) {
            result.addAll(findProvidedFieldPaths(currentPath, fieldName));
        }

        return result;
    }

    /**
     * Checks if a field can be accessed given the current path.
     * A field with @require can only be accessed if the path includes a LookupMoveEdge
     * that provides the required arguments for that field.
     *
     * @require fields MUST always be resolved from a different subgraph, even if the
     * required field is @shareable and exists in the current subgraph.
     */
    private boolean canAccessField(OperationPath currentPath, FieldMoveEdge edge) {
        String fieldName = edge.fieldName();
        String typeName = edge.source().typeName();
        String subgraph = edge.source().subgraph();

        // Check if this field has @require
        if (!graph.fieldHasRequire(typeName, subgraph, fieldName)) {
            // No @require, field can be accessed directly
            return true;
        }

        // Field has @require - check if we've gone through a lookup that provides it
        for (Edge pathEdge : currentPath.getEdges()) {
            if (pathEdge instanceof LookupMoveEdge lookupEdge) {
                // Check if this lookup targets the same type/subgraph and has @require for our field
                if (lookupEdge.target().typeName().equals(typeName)
                    && lookupEdge.target().subgraph().equals(subgraph)) {
                    for (var req : lookupEdge.requires()) {
                        if (fieldName.equals(req.fieldName())) {
                            // Found a lookup that provides the @require arguments
                            return true;
                        }
                    }
                }
            }
        }

        // Field has @require but no lookup in path provides it - can't access directly
        return false;
    }

    /**
     * Finds paths to a field that is provided via @provides directive.
     * The field is available only if the current path traversed through a providing field.
     * Handles nested field selections and type conditions from the full FieldSelectionSet.
     */
    private List<OperationPath> findProvidedFieldPaths(OperationPath currentPath, String fieldName) {
        List<OperationPath> result = new ArrayList<>();
        Node currentNode = currentPath.tail();
        String typeContext = currentPath.typeContext();

        // Check if any edge in the path provides this field
        for (Edge edge : currentPath.getEdges()) {
            if (edge instanceof FieldMoveEdge fieldEdge) {
                // Check if this edge provides the field we're looking for
                // Pass typeContext to handle inline fragments with type conditions
                if (graph.providesField(fieldEdge, fieldName, typeContext)) {
                    // The field is provided - check if we're at the right target node
                    if (fieldEdge.target().equals(currentNode)) {
                        // Create a synthetic field edge for the provided field (self-referential for scalars)
                        FieldMoveEdge providedFieldEdge = new FieldMoveEdge(
                            fieldName, currentNode, currentNode, 1
                        );
                        OperationPath newPath = currentPath.advance(providedFieldEdge);
                        result.add(newPath);
                    }
                }
            }
        }

        return result;
    }
    
    /**
     * Finds indirect paths to a field via lookup edges.
     * Uses BFS-style exploration with cycle prevention.
     *
     * @param currentPath the starting path
     * @param fieldName the field to find
     * @param excludedSubgraphs subgraphs to exclude (for cycle prevention)
     * @return list of indirect paths
     */
    public List<OperationPath> findIndirectPaths(
        OperationPath currentPath,
        String fieldName,
        Set<String> excludedSubgraphs
    ) {
        BestPathTracker tracker = new BestPathTracker();

        // Queue: (visited subgraphs, visited key requirements, visited lookups, current path)
        Deque<IndirectPathState> queue = new ArrayDeque<>();
        queue.add(new IndirectPathState(
            new HashSet<>(excludedSubgraphs),
            new HashSet<>(),
            new HashSet<>(),
            currentPath
        ));

        // Safety limit to prevent infinite loops
        int maxIterations = 1000;
        int iterations = 0;

        while (!queue.isEmpty() && iterations++ < maxIterations) {
            IndirectPathState state = queue.poll();
            OperationPath path = state.path();
            Node currentNode = path.tail();

            // Collect lookup edges from the current node
            List<LookupMoveEdge> lookupEdges = new ArrayList<>(graph.lookupEdgesFrom(currentNode).toList());

            // If type context is set, also check lookup edges from the narrowed type
            // This enables cross-subgraph resolution for inline fragments
            String typeContext = path.typeContext();
            if (typeContext != null) {
                Node narrowedNode = new Node(typeContext, currentNode.subgraph());
                graph.lookupEdgesFrom(narrowedNode).forEach(lookupEdges::add);

                // Also check interfaces/unions for the typeContext type
                // This is needed when at an abstract type (union/interface) with a concrete typeContext
                Set<String> typeContextInterfaces = graph.getInterfacesForType(typeContext);
                for (String interfaceName : typeContextInterfaces) {
                    Node interfaceNode = new Node(interfaceName, currentNode.subgraph());
                    graph.lookupEdgesFrom(interfaceNode).forEach(lookupEdges::add);
                }
                Set<String> typeContextUnions = graph.getUnionsForType(typeContext);
                for (String unionName : typeContextUnions) {
                    Node unionNode = new Node(unionName, currentNode.subgraph());
                    graph.lookupEdgesFrom(unionNode).forEach(lookupEdges::add);
                }
            }

            // If the current type implements interfaces, also check lookup edges from those interfaces
            // This enables concrete types to use interface lookup edges (e.g., Book can use Content lookup)
            Set<String> interfaces = graph.getInterfacesForType(currentNode.typeName());
            for (String interfaceName : interfaces) {
                Node interfaceNode = new Node(interfaceName, currentNode.subgraph());
                graph.lookupEdgesFrom(interfaceNode).forEach(lookupEdges::add);
            }

            // If the current type is a member of unions, also check lookup edges from those unions
            // This enables concrete types to use union lookup edges (e.g., Book can use Media lookup)
            Set<String> unions = graph.getUnionsForType(currentNode.typeName());
            for (String unionName : unions) {
                Node unionNode = new Node(unionName, currentNode.subgraph());
                graph.lookupEdgesFrom(unionNode).forEach(lookupEdges::add);
            }

            // Look for LookupMoveEdges
            for (LookupMoveEdge lookupEdge : lookupEdges) {
                String targetSubgraph = lookupEdge.target().subgraph();

                // Skip if we've already followed this exact lookup edge
                String lookupKey = lookupEdge.source() + "->" + lookupEdge.target() + ":" + lookupEdge.lookupField();
                if (state.visitedLookups().contains(lookupKey)) {
                    continue;
                }

                // Skip if we've already checked similar requirements
                // But allow lookups with @require even if lookup arguments were visited -
                // we need to follow them to get the @require data from another subgraph
                Set<String> lookupArgNames = lookupEdge.lookupArguments().stream()
                    .map(arg -> arg.argumentName())
                    .collect(java.util.stream.Collectors.toSet());
                boolean hasRequirements = lookupEdge.hasRequirements();
                if (!hasRequirements && !lookupArgNames.isEmpty() && state.visitedKeyFields().containsAll(lookupArgNames)) {
                    continue;
                }
                
                // Check if we can satisfy the lookup requirements
                if (!canSatisfyRequirements(state.path(), lookupEdge)) {
                    continue;
                }
                
                // Advance path through the lookup
                OperationPath nextPath = state.path().advance(lookupEdge);
                
                // Try to find direct path from new position
                List<OperationPath> directPaths = findDirectPaths(nextPath, fieldName);
                
                if (!directPaths.isEmpty()) {
                    // Found resolution! Add to tracker
                    for (OperationPath directPath : directPaths) {
                        tracker.add(directPath);
                    }
                } else {
                    // Need to go deeper - add to queue
                    Set<String> newVisitedSubgraphs = new HashSet<>(state.visitedSubgraphs());
                    newVisitedSubgraphs.add(targetSubgraph);

                    Set<String> newVisitedKeyFields = new HashSet<>(state.visitedKeyFields());
                    newVisitedKeyFields.addAll(lookupArgNames);

                    Set<String> newVisitedLookups = new HashSet<>(state.visitedLookups());
                    newVisitedLookups.add(lookupKey);

                    queue.add(new IndirectPathState(
                        newVisitedSubgraphs,
                        newVisitedKeyFields,
                        newVisitedLookups,
                        nextPath
                    ));
                }
            }
        }
        
        return tracker.getBestPaths();
    }
    
    /**
     * Checks if the requirements of a lookup edge can be satisfied from the current path.
     *
     * For single-alternative arguments (simple paths, object selections), ALL paths must be resolvable.
     * For multi-alternative arguments (e.g., {@code <Book>.isbn | <Electronics>.sku}),
     * at least ONE alternative must be fully resolvable. This is because alternatives represent
     * different type-conditioned paths and only the matching one is used at runtime.
     */
    private boolean canSatisfyRequirements(OperationPath path, LookupMoveEdge lookupEdge) {
        if (!lookupEdge.hasLookupArguments()) {
            return true;
        }

        Node sourceNode = path.tail();
        String typeContext = path.typeContext();

        for (var lookupArg : lookupEdge.lookupArguments()) {
            var alternatives = lookupArg.selection().alternatives();

            if (alternatives.size() <= 1) {
                // Single alternative: all paths must be resolvable
                for (var argPath : lookupArg.extractPaths()) {
                    if (!canResolvePath(sourceNode, argPath, typeContext)) {
                        return false;
                    }
                }
            } else {
                // Multiple alternatives: ANY alternative must be fully resolvable
                boolean anyAlternativeResolvable = false;
                for (var alt : alternatives) {
                    var singleAlt = new SelectedValue(alt);
                    boolean allPathsInAlt = true;
                    for (var altPath : singleAlt.extractPaths()) {
                        if (!canResolvePath(sourceNode, altPath, typeContext)) {
                            allPathsInAlt = false;
                            break;
                        }
                    }
                    if (allPathsInAlt) {
                        anyAlternativeResolvable = true;
                        break;
                    }
                }
                if (!anyAlternativeResolvable) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Checks if a Path can be resolved from a node, respecting type conditions.
     * Also checks implementing types when a field is not found on an interface.
     */
    private boolean canResolvePath(Node startNode, Path path, String typeContext) {
        Node currentNode = startNode;

        for (PathSegment segment : path.segments()) {
            String fieldName = segment.fieldName();
            String segmentTypeName = segment.typeCondition();

            // If segment has a type condition, check if it matches
            if (segmentTypeName != null) {
                // Type condition must match either the current node's type or the typeContext
                boolean typeMatches = segmentTypeName.equals(currentNode.typeName())
                    || segmentTypeName.equals(typeContext);
                if (!typeMatches) {
                    return false;
                }
                // If we have a typeContext that matches, use it as the source node
                if (segmentTypeName.equals(typeContext) && !segmentTypeName.equals(currentNode.typeName())) {
                    currentNode = new Node(typeContext, currentNode.subgraph());
                }
            }

            // Find edge for this field
            var matchingEdge = graph.fieldEdgesFrom(currentNode)
                .filter(edge -> edge.fieldName().equals(fieldName))
                .findFirst();

            if (matchingEdge.isEmpty()) {
                // If we have a typeContext, also try from the narrowed node
                if (typeContext != null && !typeContext.equals(currentNode.typeName())) {
                    Node narrowedNode = new Node(typeContext, currentNode.subgraph());
                    matchingEdge = graph.fieldEdgesFrom(narrowedNode)
                        .filter(edge -> edge.fieldName().equals(fieldName))
                        .findFirst();
                }
                if (matchingEdge.isEmpty()) {
                    // Field not found on current type - check implementing types
                    // This handles cases like @require(field: "data.bar") where data returns
                    // interface Foo, and bar is a field on implementing type Bar
                    Set<String> implTypes = graph.getImplementingTypesForInterface(currentNode.typeName());
                    boolean foundOnImplementingType = false;
                    for (String implType : implTypes) {
                        Node implNode = new Node(implType, currentNode.subgraph());
                        var implEdge = graph.fieldEdgesFrom(implNode)
                            .filter(e -> e.fieldName().equals(fieldName))
                            .findFirst();
                        if (implEdge.isPresent()) {
                            foundOnImplementingType = true;
                            currentNode = implEdge.get().target();
                            break;
                        }
                    }
                    if (!foundOnImplementingType) {
                        return false;
                    }
                    continue; // Skip the update at the end since we already updated currentNode
                }
            }

            // Move to the target node for the next segment
            currentNode = matchingEdge.get().target();
        }

        return true;
    }

    /**
     * State record for indirect path finding.
     */
    private record IndirectPathState(
        Set<String> visitedSubgraphs,
        Set<String> visitedKeyFields,
        Set<String> visitedLookups,
        OperationPath path
    ) {}
}
