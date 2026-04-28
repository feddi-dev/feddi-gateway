package dev.feddi.federation.engine.compose;

import dev.feddi.federation.engine.graph.Graph;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import graphql.schema.GraphQLSchema;

import java.util.List;

/**
 * Result of schema composition.
 *
 * @param subgraphs the parsed subgraphs
 * @param graph the planning graph (null if composition failed)
 * @param supergraph the merged consumer-facing schema (null if composition failed)
 * @param validationResult the validation result
 */
public record CompositionResult(
    List<Subgraph> subgraphs,
    Graph graph,
    GraphQLSchema supergraph,
    ValidationResult validationResult
) {

    /**
     * Checks if composition was successful.
     */
    public boolean isSuccess() {
        return validationResult.isValid() && graph != null && supergraph != null;
    }

    /**
     * Checks if composition failed.
     */
    public boolean isFailure() {
        return !isSuccess();
    }

    /**
     * Creates a successful composition result.
     */
    public static CompositionResult success(
            List<Subgraph> subgraphs,
            Graph graph,
            GraphQLSchema supergraph,
            ValidationResult validationResult) {
        return new CompositionResult(subgraphs, graph, supergraph, validationResult);
    }

    /**
     * Creates a failed composition result.
     */
    public static CompositionResult failure(List<Subgraph> subgraphs, ValidationResult validationResult) {
        return new CompositionResult(subgraphs, null, null, validationResult);
    }

    @Override
    public String toString() {
        if (isSuccess()) {
            return String.format("CompositionResult(success, subgraphs=%d, graph=%s)",
                subgraphs.size(), graph);
        }
        return String.format("CompositionResult(failed, errors=%d)",
            validationResult.errors().size());
    }
}
