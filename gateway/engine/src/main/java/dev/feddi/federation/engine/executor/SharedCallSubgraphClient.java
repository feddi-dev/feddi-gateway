package dev.feddi.federation.engine.executor;

import graphql.ExecutionResult;
import graphql.language.OperationDefinition;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shares identical subgraph query calls within one request. Different plan steps often send
 * the same lookup with the same variables (e.g. {@code product(upc: "1")} below several
 * response paths); the first call goes to the subgraph and later ones reuse its result.
 *
 * <p>Only queries are shared, never mutations. Each consumer after the first gets a deep copy
 * of the data, so steps that write into their response positions cannot affect each other.
 *
 * <p>Instances are per request: create one per {@link Executor#execute} call.
 */
final class SharedCallSubgraphClient implements SubgraphClient {

    private record CallKey(String operation, Map<String, Object> variables) {
    }

    private static final class SharedCall {
        private final Mono<ExecutionResult> result;
        private final AtomicBoolean claimed = new AtomicBoolean();

        SharedCall(Mono<ExecutionResult> result) {
            this.result = result.cache();
        }

        Mono<ExecutionResult> next() {
            return claimed.compareAndSet(false, true) ? result : result.map(ResultCopies::copy);
        }
    }

    private final SubgraphClient delegate;
    private final Map<CallKey, SharedCall> calls = new ConcurrentHashMap<>();

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
        return calls.computeIfAbsent(key, k -> new SharedCall(delegate.execute(operation, variables))).next();
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
