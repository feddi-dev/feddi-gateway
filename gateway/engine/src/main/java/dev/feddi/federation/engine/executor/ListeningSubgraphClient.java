package dev.feddi.federation.engine.executor;

import graphql.ExecutionResult;
import graphql.language.OperationDefinition;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Reports every real subgraph request to the {@link ExecutionListener}: its duration and
 * outcome. Sits directly around the transport client, so shared or deduplicated calls are
 * counted once and a batch counts as one request.
 */
final class ListeningSubgraphClient implements SubgraphClient {

    private final SubgraphClient delegate;
    private final String subgraphName;
    private final ExecutionListener listener;

    ListeningSubgraphClient(SubgraphClient delegate, String subgraphName, ExecutionListener listener) {
        this.delegate = delegate;
        this.subgraphName = subgraphName;
        this.listener = listener;
    }

    @Override
    public Mono<ExecutionResult> execute(OperationDefinition operation, Map<String, Object> variables) {
        return Mono.defer(() -> {
            long start = System.nanoTime();
            return delegate.execute(operation, variables)
                .doOnNext(result -> listener.onSubgraphFetchComplete(subgraphName, System.nanoTime() - start, true))
                .doOnError(e -> listener.onSubgraphFetchComplete(subgraphName, System.nanoTime() - start, false));
        });
    }

    @Override
    public Mono<List<ExecutionResult>> executeBatch(OperationDefinition operation,
                                                    List<Map<String, Object>> variableSets) {
        return Mono.defer(() -> {
            long start = System.nanoTime();
            return delegate.executeBatch(operation, variableSets)
                .doOnNext(results -> listener.onSubgraphFetchComplete(subgraphName, System.nanoTime() - start, true))
                .doOnError(e -> listener.onSubgraphFetchComplete(subgraphName, System.nanoTime() - start, false));
        });
    }

    @Override
    public BatchingOptions batching() {
        return delegate.batching();
    }
}
