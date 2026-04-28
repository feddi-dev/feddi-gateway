package dev.feddi.federation.engine.graph;

/**
 * Represents a direct field resolution edge.
 * This edge is used when a field can be resolved within the same subgraph.
 *
 * @param fieldName the name of the field being resolved
 * @param source the source node
 * @param target the target node (usually same subgraph)
 * @param cost the cost of this edge (typically low, e.g., 1)
 */
public record FieldMoveEdge(
    String fieldName,
    Node source,
    Node target,
    int cost
) implements Edge {
    
    public FieldMoveEdge {
        if (fieldName == null || fieldName.isBlank()) {
            throw new IllegalArgumentException("fieldName cannot be null or blank");
        }
        if (source == null) {
            throw new IllegalArgumentException("source cannot be null");
        }
        if (target == null) {
            throw new IllegalArgumentException("target cannot be null");
        }
        if (cost < 0) {
            throw new IllegalArgumentException("cost cannot be negative");
        }
    }
    
    /**
     * Creates a FieldMoveEdge with a default cost of 1.
     */
    public static FieldMoveEdge withDefaultCost(String fieldName, Node source, Node target) {
        return new FieldMoveEdge(fieldName, source, target, 1);
    }
    
    @Override
    public String toString() {
        return String.format("FieldMove(%s: %s -> %s, cost=%d)", 
            fieldName, source, target, cost);
    }
}
