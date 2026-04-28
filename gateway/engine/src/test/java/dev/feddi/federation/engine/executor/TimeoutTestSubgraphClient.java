package dev.feddi.federation.engine.executor;

import graphql.ExecutionResult;
import graphql.language.OperationDefinition;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Wraps a SubgraphClient with timeout behavior for testing.
 * When a timeout occurs, throws SubgraphTimeoutException which can be caught
 * by the executor for partial failure handling.
 */
public class TimeoutTestSubgraphClient implements SubgraphClient {

    private final SubgraphClient delegate;
    private final String subgraphName;
    private final Duration timeout;

    public TimeoutTestSubgraphClient(SubgraphClient delegate, String subgraphName, Duration timeout) {
        this.delegate = delegate;
        this.subgraphName = subgraphName;
        this.timeout = timeout;
    }

    @Override
    public Mono<ExecutionResult> execute(OperationDefinition operation, Map<String, Object> variables) {
        return delegate.execute(operation, variables)
            .timeout(timeout)
            .onErrorMap(TimeoutException.class,
                e -> new SubgraphTimeoutException(subgraphName, timeout, e));
    }

    public String subgraphName() {
        return subgraphName;
    }

    public Duration timeout() {
        return timeout;
    }
}
