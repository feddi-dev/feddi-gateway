package dev.feddi.federation.engine.compose.validation;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.graph.Graph;
import graphql.schema.GraphQLSchema;

import java.util.List;

/**
 * Interface for post-graph validation rules.
 *
 * Post-graph rules validate the composed schema after the planning graph has been built.
 * This is the final validation phase and can check satisfiability - whether all fields
 * in the schema can be resolved by the query planner.
 */
public interface PostGraphValidationRule {

    /**
     * Gets the rule name/identifier.
     */
    String name();

    /**
     * Validates the composed schema using the planning graph.
     *
     * @param graph the planning graph built from all subgraphs
     * @param mergedSchema the merged supergraph schema
     * @param subgraphs the original subgraphs (for context in error messages)
     * @return validation result with any diagnostics
     */
    ValidationResult validate(Graph graph, GraphQLSchema mergedSchema, List<Subgraph> subgraphs);
}
