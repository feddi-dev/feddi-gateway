package dev.feddi.federation.engine.planner;

import dev.feddi.federation.engine.graph.Edge;
import dev.feddi.federation.engine.graph.LookupMoveEdge;
import dev.feddi.federation.engine.graph.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Represents a path through the query planning graph.
 * Tracks visited edges for cycle detection and accumulated cost.
 *
 * <h3>Type Context</h3>
 * <p>The {@code typeContext} tracks the concrete type narrowing from inline fragments in the
 * GraphQL operation. In the planning graph, nodes represent (typeName, subgraph) pairs using
 * the abstract type (e.g., {@code Content/reviews}). When the operation contains an inline
 * fragment like {@code ... on Article { title }}, the planner needs to know that fields should
 * be resolved against {@code Article}, not the abstract {@code Content}.
 *
 * <p>The type context is set by {@link #withTypeContext(String)} when the planner enters an
 * inline fragment with a type condition. PathFinder uses it in three ways:
 * <ul>
 *   <li>Finding field edges: if no edge exists from the abstract node (e.g., {@code Content/reviews}),
 *       it falls back to searching from the narrowed node ({@code Article/reviews})</li>
 *   <li>Finding lookup edges: it also searches for lookups from the narrowed type and its
 *       interfaces/unions, enabling cross-subgraph resolution for concrete types</li>
 *   <li>Resolving lookup arguments: when checking if a lookup's {@code @is} argument paths can be
 *       satisfied, type conditions in the path are matched against the type context</li>
 * </ul>
 *
 * <p>The type context is preserved through {@link dev.feddi.federation.engine.graph.LookupMoveEdge}s
 * (which cross subgraphs for the same entity type) but cleared when advancing through field edges
 * (which move to a different return type).
 */
public final class OperationPath {

    private final Node rootNode;
    private final PathSegment lastSegment;
    private final Set<Edge> visitedEdges;
    private final String typeContext;

    private OperationPath(Node rootNode, PathSegment lastSegment, Set<Edge> visitedEdges, String typeContext) {
        this.rootNode = rootNode;
        this.lastSegment = lastSegment;
        this.visitedEdges = Set.copyOf(visitedEdges);
        this.typeContext = typeContext;
    }
    
    /**
     * Creates a new path starting at the given root node.
     */
    public static OperationPath startAt(Node rootNode) {
        return new OperationPath(rootNode, null, Set.of(), null);
    }

    /**
     * Creates a new path with the first edge.
     */
    public static OperationPath withFirstEdge(Node rootNode, Edge firstEdge) {
        PathSegment segment = PathSegment.root(firstEdge);
        return new OperationPath(rootNode, segment, Set.of(firstEdge), null);
    }

    /**
     * Advances this path by adding a new edge.
     * For lookup edges, preserves the type context (same entity type, different subgraph).
     * For field edges, clears the type context (the field's return type is a new type scope).
     */
    public OperationPath advance(Edge edge) {
        PathSegment newSegment;
        if (lastSegment == null) {
            newSegment = PathSegment.root(edge);
        } else {
            newSegment = lastSegment.extend(edge);
        }

        Set<Edge> newVisited = new HashSet<>(visitedEdges);
        newVisited.add(edge);

        // Preserve type context through lookup edges (cross-subgraph for same entity type)
        // Clear it for field edges (moving to a different type)
        String newTypeContext = (edge instanceof LookupMoveEdge) ? typeContext : null;

        return new OperationPath(rootNode, newSegment, newVisited, newTypeContext);
    }

    /**
     * Creates a new path with a narrowed type context from an inline fragment.
     *
     * @param typeContext the concrete type name from the inline fragment's type condition
     *                    (e.g., "Article" from {@code ... on Article})
     */
    public OperationPath withTypeContext(String typeContext) {
        return new OperationPath(rootNode, lastSegment, visitedEdges, typeContext);
    }

    /**
     * Gets the type context (narrowed concrete type from an inline fragment), or {@code null}
     * if no type narrowing is active.
     */
    public String typeContext() {
        return typeContext;
    }
    
    /**
     * Gets the root node where this path starts.
     */
    public Node rootNode() {
        return rootNode;
    }
    
    /**
     * Gets the current tail node (where we are now).
     */
    public Node tail() {
        return lastSegment != null ? lastSegment.targetNode() : rootNode;
    }
    
    /**
     * Gets the current subgraph.
     */
    public String currentSubgraph() {
        return tail().subgraph();
    }
    
    /**
     * Gets the total cost of this path.
     */
    public int cost() {
        return lastSegment != null ? lastSegment.cumulativeCost() : 0;
    }
    
    /**
     * Checks if this path is empty (no edges).
     */
    public boolean isEmpty() {
        return lastSegment == null;
    }
    
    /**
     * Checks if this path has visited the given edge.
     */
    public boolean hasVisitedEdge(Edge edge) {
        return visitedEdges.contains(edge);
    }
    
    /**
     * Checks if this path has visited a subgraph.
     */
    public boolean hasVisitedSubgraph(String subgraph) {
        return getVisitedSubgraphs().contains(subgraph);
    }
    
    /**
     * Gets all subgraphs visited by this path.
     */
    public Set<String> getVisitedSubgraphs() {
        Set<String> subgraphs = new HashSet<>();
        subgraphs.add(rootNode.subgraph());
        PathSegment current = lastSegment;
        while (current != null) {
            subgraphs.add(current.targetSubgraph());
            current = current.previous();
        }
        return subgraphs;
    }
    
    /**
     * Gets all edges in this path, from first to last.
     */
    public List<Edge> getEdges() {
        List<Edge> edges = new ArrayList<>();
        PathSegment current = lastSegment;
        while (current != null) {
            edges.add(current.edge());
            current = current.previous();
        }
        Collections.reverse(edges);
        return edges;
    }
    
    /**
     * Gets all segments in this path, from first to last.
     */
    public List<PathSegment> getSegments() {
        List<PathSegment> segments = new ArrayList<>();
        PathSegment current = lastSegment;
        while (current != null) {
            segments.add(current);
            current = current.previous();
        }
        Collections.reverse(segments);
        return segments;
    }
    
    /**
     * Gets the depth of this path (number of edges).
     */
    public int depth() {
        return lastSegment != null ? lastSegment.depth() : 0;
    }
    
    /**
     * Creates a copy of visited subgraphs for modification.
     */
    public Set<String> copyVisitedSubgraphs() {
        return new HashSet<>(getVisitedSubgraphs());
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OperationPath that)) return false;
        return cost() == that.cost() &&
               Objects.equals(rootNode, that.rootNode) &&
               Objects.equals(visitedEdges, that.visitedEdges);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(rootNode, visitedEdges, cost());
    }
    
    @Override
    public String toString() {
        if (isEmpty()) {
            return String.format("Path(root=%s, cost=0)", rootNode);
        }
        
        List<String> edgeNames = getEdges().stream()
            .map(e -> e.fieldName())
            .toList();
        
        return String.format("Path(%s -> %s, cost=%d)", 
            rootNode, String.join(" -> ", edgeNames), cost());
    }
}
