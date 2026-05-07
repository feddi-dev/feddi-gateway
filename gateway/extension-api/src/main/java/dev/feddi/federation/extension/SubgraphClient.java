package dev.feddi.federation.extension;

import graphql.ExecutionResult;
import graphql.language.OperationDefinition;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Client interface for executing GraphQL operations against a subgraph.
 *
 * <p>Implementations are responsible for making HTTP requests to the subgraph
 * and returning the response. Extension implementations can add features like
 * authentication, logging, caching, or circuit breakers.
 */
public interface SubgraphClient {

    /**
     * Executes a GraphQL operation against the subgraph.
     *
     * @param operation the parsed GraphQL operation definition
     * @param variables the variables for the operation (may be empty)
     * @return a Mono containing the GraphQL execution result
     */
    Mono<ExecutionResult> execute(OperationDefinition operation, Map<String, Object> variables, FeddiGatewayRequestContext context);
}
