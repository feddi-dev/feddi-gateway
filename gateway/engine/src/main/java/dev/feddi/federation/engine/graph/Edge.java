package dev.feddi.federation.engine.graph;

/**
 * Sealed interface representing an edge in the query planning graph.
 * Edges represent possible transitions between nodes (type instances in subgraphs).
 */
public sealed interface Edge permits FieldMoveEdge, LookupMoveEdge {
    
    /**
     * The cost of traversing this edge.
     * Lower costs are preferred during path finding.
     */
    int cost();
    
    /**
     * The source node of this edge.
     */
    Node source();
    
    /**
     * The target node of this edge.
     */
    Node target();
    
    /**
     * The field name associated with this edge.
     */
    String fieldName();
}
