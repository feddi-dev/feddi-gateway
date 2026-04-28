package dev.feddi.federation.engine.executor;

import graphql.ExecutionResult;
import graphql.language.OperationDefinition;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Interface for executing GraphQL operations against a subgraph.
 *
 * Implementations handle the actual communication with subgraphs,
 * whether that's HTTP, in-memory, or mocked responses.
 */
public interface SubgraphClient {

    /**
     * Executes a GraphQL operation against the subgraph.
     *
     * @param operation the GraphQL operation to execute
     * @param variables the variables for the operation
     * @return the execution result from the subgraph wrapped in a Mono
     */
    Mono<ExecutionResult> execute(OperationDefinition operation, Map<String, Object> variables);
}
