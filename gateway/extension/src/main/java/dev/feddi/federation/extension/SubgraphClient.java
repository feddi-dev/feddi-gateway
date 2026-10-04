package dev.feddi.federation.extension;

import graphql.ExecutionResult;
import graphql.language.OperationDefinition;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
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

    /**
     * Executes the same operation once per variable set. The feddi Gateway calls this for
     * subgraphs configured with {@code batching: variables}; the results must be returned in
     * the order of {@code variableSets}.
     *
     * <p>The default sends one {@link #execute} call per variable set, so existing
     * implementations keep working unchanged. Implementations that support variable batching
     * (a single request with a {@code variables} array) override this. Wrapping clients
     * (e.g. adding timeouts or auth) should delegate this method as well, otherwise batches
     * fall back to one request per variable set.
     *
     * @param operation    the parsed GraphQL operation definition
     * @param variableSets one variables map per entity
     * @param context      the request context
     * @return a Mono containing one result per variable set, in order
     */
    default Mono<List<ExecutionResult>> executeBatch(OperationDefinition operation,
                                                     List<Map<String, Object>> variableSets,
                                                     FeddiGatewayRequestContext context) {
        return Flux.fromIterable(variableSets)
            .flatMapSequential(variables -> execute(operation, variables, context))
            .collectList();
    }
}
