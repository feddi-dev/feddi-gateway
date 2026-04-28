package dev.feddi.federation.engine.compose.validation;

import dev.feddi.federation.engine.compose.Subgraph;
import graphql.schema.GraphQLSchema;

import java.util.List;

/**
 * Interface for post-merge validation rules.
 * 
 * Post-merge rules validate the composed supergraph schema after all subgraphs
 * have been merged together.
 */
public interface PostMergeValidationRule {
    
    /**
     * Gets the rule name/identifier.
     */
    String name();
    
    /**
     * Validates the merged schema and returns any diagnostics.
     *
     * @param mergedSchema the merged supergraph schema
     * @param subgraphs the original subgraphs (for context in error messages)
     * @return validation result with any diagnostics
     */
    ValidationResult validate(GraphQLSchema mergedSchema, List<Subgraph> subgraphs);
}
