package dev.feddi.federation.extension;

import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.GraphqlErrorBuilder;
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
     * <p>Every implementation decides explicitly how to batch:
     * <ul>
     *   <li>send a single request with a {@code variables} array (variable batching),</li>
     *   <li>delegate to the wrapped client, for clients that wrap another one (e.g. adding auth),
     *       so its batching is kept,</li>
     *   <li>or send one request per variable set with {@link #executeEach}.</li>
     * </ul>
     *
     * @param operation    the parsed GraphQL operation definition
     * @param variableSets one variables map per entity
     * @param context      the request context
     * @return a Mono containing one result per variable set, in order
     */
    Mono<List<ExecutionResult>> executeBatch(OperationDefinition operation,
                                             List<Map<String, Object>> variableSets,
                                             FeddiGatewayRequestContext context);

    /**
     * Executes the operation once per variable set with {@link #execute}, for clients without
     * variable batching. A failed request (also one whose {@code execute} throws) becomes an error
     * result for its variable set only; the other results are kept.
     *
     * <p>The feddi Gateway applies the subgraph timeout to the whole batch, so a single request
     * that does not complete in time fails all of them. For clients that use this method,
     * {@code batching: none} (a timeout per request) or {@code batching: alias} (one request per
     * batch) are the better choices.
     *
     * @param client       the client to execute with
     * @param operation    the parsed GraphQL operation definition
     * @param variableSets one variables map per entity
     * @param context      the request context
     * @return a Mono containing one result per variable set, in order
     */
    static Mono<List<ExecutionResult>> executeEach(SubgraphClient client,
                                                   OperationDefinition operation,
                                                   List<Map<String, Object>> variableSets,
                                                   FeddiGatewayRequestContext context) {
        return Flux.fromIterable(variableSets)
            .flatMapSequential(variables -> Mono.defer(() -> client.execute(operation, variables, context))
                .onErrorResume(e -> Mono.just(ExecutionResultImpl.newExecutionResult()
                    .addError(GraphqlErrorBuilder.newError()
                        .message("Subgraph request failed: " + e.getMessage())
                        .build())
                    .build())))
            .collectList();
    }
}
