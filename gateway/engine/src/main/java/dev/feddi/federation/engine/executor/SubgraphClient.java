package dev.feddi.federation.engine.executor;

import graphql.ExecutionResult;
import graphql.language.OperationDefinition;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
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

    /**
     * Executes the same operation once per variable set, preferably in a single request
     * (variable batching). The results are returned in the order of {@code variableSets}.
     *
     * <p>The default sends one {@link #execute} call per variable set. Clients that support
     * variable batching override this.
     *
     * @param operation    the GraphQL operation to execute
     * @param variableSets one variables map per entity
     * @return one result per variable set, in order
     */
    default Mono<List<ExecutionResult>> executeBatch(OperationDefinition operation,
                                                     List<Map<String, Object>> variableSets) {
        return Flux.fromIterable(variableSets)
            .flatMapSequential(variables -> execute(operation, variables))
            .collectList();
    }

    /**
     * How the executor should batch entity lookups for this subgraph.
     */
    default BatchingOptions batching() {
        return BatchingOptions.NONE;
    }
}
