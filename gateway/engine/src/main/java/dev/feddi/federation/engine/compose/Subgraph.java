package dev.feddi.federation.engine.compose;

import graphql.schema.GraphQLSchema;

/**
 * Represents a parsed subgraph with its metadata and schema.
 * This is a thin wrapper around GraphQL Java's GraphQLSchema.
 *
 * @param name the subgraph identifier (e.g., "products", "inventory")
 * @param url the subgraph endpoint URL
 * @param schema the parsed GraphQL schema
 */
public record Subgraph(
    String name,
    String url,
    GraphQLSchema schema
) {

    public Subgraph {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name cannot be null or blank");
        }
        if (schema == null) {
            throw new IllegalArgumentException("schema cannot be null");
        }
        // url can be null for testing purposes
    }

    /**
     * Creates a Subgraph without a URL.
     */
    public static Subgraph of(String name, GraphQLSchema schema) {
        return new Subgraph(name, null, schema);
    }

    @Override
    public String toString() {
        return String.format("Subgraph(%s, url=%s)", name, url);
    }
}
