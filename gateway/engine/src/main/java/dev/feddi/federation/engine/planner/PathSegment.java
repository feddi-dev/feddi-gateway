package dev.feddi.federation.engine.planner;

import dev.feddi.federation.engine.graph.Edge;
import dev.feddi.federation.engine.graph.Node;

/**
 * Represents a single step/segment in an operation path.
 * Forms a linked list structure for efficient path manipulation.
 *
 * @param edge the edge traversed in this segment
 * @param previous the previous segment (null for the first segment)
 * @param cumulativeCost the total cost up to and including this segment
 */
public record PathSegment(
    Edge edge,
    PathSegment previous,
    int cumulativeCost
) {
    
    public PathSegment {
        if (edge == null) {
            throw new IllegalArgumentException("edge cannot be null");
        }
        if (cumulativeCost < 0) {
            throw new IllegalArgumentException("cumulativeCost cannot be negative");
        }
    }
    
    /**
     * Creates a root segment (first segment in a path).
     */
    public static PathSegment root(Edge edge) {
        return new PathSegment(edge, null, edge.cost());
    }
    
    /**
     * Creates a new segment extending from this segment.
     */
    public PathSegment extend(Edge nextEdge) {
        return new PathSegment(nextEdge, this, this.cumulativeCost + nextEdge.cost());
    }
    
    /**
     * Gets the target node of this segment.
     */
    public Node targetNode() {
        return edge.target();
    }
    
    /**
     * Gets the source node of this segment.
     */
    public Node sourceNode() {
        return edge.source();
    }
    
    /**
     * Gets the subgraph of the target node.
     */
    public String targetSubgraph() {
        return edge.target().subgraph();
    }
    
    /**
     * Checks if this is the first segment (no previous).
     */
    public boolean isFirst() {
        return previous == null;
    }
    
    /**
     * Counts the depth of this segment (number of segments from root).
     */
    public int depth() {
        int depth = 1;
        PathSegment current = previous;
        while (current != null) {
            depth++;
            current = current.previous();
        }
        return depth;
    }
    
    @Override
    public String toString() {
        return String.format("Segment(%s -> %s, cost=%d)", 
            edge.source(), edge.target(), cumulativeCost);
    }
}
