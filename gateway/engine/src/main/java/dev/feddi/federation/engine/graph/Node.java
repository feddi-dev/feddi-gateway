package dev.feddi.federation.engine.graph;

/**
 * Represents a node in the query planning graph.
 * A node is a type instance in a specific subgraph.
 *
 * @param typeName the GraphQL type name (e.g., "User", "Product")
 * @param subgraph the subgraph where this type instance exists
 */
public record Node(String typeName, String subgraph) {
    
    public Node {
        if (typeName == null || typeName.isBlank()) {
            throw new IllegalArgumentException("typeName cannot be null or blank");
        }
        if (subgraph == null || subgraph.isBlank()) {
            throw new IllegalArgumentException("subgraph cannot be null or blank");
        }
    }
    
    @Override
    public String toString() {
        return typeName + "/" + subgraph;
    }
}
