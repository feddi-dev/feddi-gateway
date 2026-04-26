package dev.feddi.federation.engine.testcase;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.graph.Graph;
import graphql.schema.GraphQLSchema;

import java.util.List;
import java.util.Map;

/**
 * Represents a schema definition loaded from YAML.
 *
 * @param name the name of the schema
 * @param description optional description
 * @param graph the graph model
 * @param subgraphs the list of parsed subgraphs
 * @param subgraphSchemas map of subgraph name to its GraphQL schema for validation
 * @param supergraphSchema the merged supergraph schema for normalization
 */
public record SchemaDefinition(
    String name,
    String description,
    Graph graph,
    List<Subgraph> subgraphs,
    Map<String, GraphQLSchema> subgraphSchemas,
    GraphQLSchema supergraphSchema
) {

    public SchemaDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name cannot be null or blank");
        }
        if (graph == null) {
            throw new IllegalArgumentException("graph cannot be null");
        }
        if (supergraphSchema == null) {
            throw new IllegalArgumentException("supergraphSchema cannot be null");
        }
        subgraphs = subgraphs == null ? List.of() : List.copyOf(subgraphs);
        subgraphSchemas = subgraphSchemas == null ? Map.of() : Map.copyOf(subgraphSchemas);
    }

    /**
     * Gets the GraphQL schema for a specific subgraph.
     */
    public GraphQLSchema getSubgraphSchema(String subgraphName) {
        return subgraphSchemas.get(subgraphName);
    }

    @Override
    public String toString() {
        return String.format("Schema[%s]", name);
    }
}
