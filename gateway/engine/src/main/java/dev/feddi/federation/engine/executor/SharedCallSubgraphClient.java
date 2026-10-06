package dev.feddi.federation.engine.executor;

import graphql.ExecutionResult;
import graphql.language.OperationDefinition;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shares identical subgraph query calls within one request. Different plan steps often send
 * the same lookup with the same variables (e.g. {@code product(upc: "1")} below several
 * response paths); the first call goes to the subgraph and later ones reuse its result.
 *
 * <p>Only queries are shared, never mutations. Every consumer gets its own deep copy of the
 * data and the cached result stays untouched: the executor merges result maps into the response
 * and later steps write into them, so a consumer that subscribes later must not see another
 * consumer's changes.
 *
 * <p>Instances are per request: create one per {@link Executor#execute} call.
 */
final class SharedCallSubgraphClient implements SubgraphClient {

    private record CallKey(String operation, Map<String, Object> variables) {
    }

    private final SubgraphClient delegate;
    private final Map<CallKey, Mono<ExecutionResult>> calls = new ConcurrentHashMap<>();

    SharedCallSubgraphClient(SubgraphClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public Mono<ExecutionResult> execute(OperationDefinition operation, Map<String, Object> variables) {
        if (operation.getOperation() != OperationDefinition.Operation.QUERY) {
            return delegate.execute(operation, variables);
        }
        String text = OperationTexts.compact(operation);
        CallKey key = new CallKey(text, variables == null ? Map.of() : variables);
        return calls.computeIfAbsent(key, k -> delegate.execute(operation, variables).cache())
            .map(ResultCopies::copy);
    }

    /**
     * Batched requests are already deduplicated per step and are not shared between steps.
     */
    @Override
    public Mono<List<ExecutionResult>> executeBatch(OperationDefinition operation,
                                                    List<Map<String, Object>> variableSets) {
        return delegate.executeBatch(operation, variableSets);
    }

    @Override
    public BatchingOptions batching() {
        return delegate.batching();
    }
}
