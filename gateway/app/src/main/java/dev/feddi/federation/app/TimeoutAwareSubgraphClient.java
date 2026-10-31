package dev.feddi.federation.app;

import dev.feddi.federation.extension.FeddiGatewayRequestContext;
import dev.feddi.federation.extension.SubgraphClient;
import dev.feddi.federation.engine.executor.SubgraphTimeoutException;
import graphql.ExecutionResult;
import graphql.language.OperationDefinition;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.internal.ThreadExecutorMap;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

/**
 * Wraps a SubgraphClient with timeout behavior.
 * When a timeout occurs, throws SubgraphTimeoutException which can be caught
 * by the executor for partial failure handling.
 */
public class TimeoutAwareSubgraphClient implements SubgraphClient {

    /**
     * Timers on Netty event loops, by loop. A call started on an event loop sets and cancels its timeout there: no
     * task queued to another thread (as with {@link Schedulers#parallel()}) on every subgraph call.
     */
    private static final Map<EventExecutor, Scheduler> LOOP_TIMERS = new ConcurrentHashMap<>();

    private final SubgraphClient delegate;
    private final String subgraphName;
    private final Duration timeout;

    public TimeoutAwareSubgraphClient(SubgraphClient delegate, String subgraphName, Duration timeout) {
        this.delegate = delegate;
        this.subgraphName = subgraphName;
        this.timeout = timeout;
    }

    @Override
    public Mono<ExecutionResult> execute(OperationDefinition operation, Map<String, Object> variables, FeddiGatewayRequestContext context) {
        return delegate.execute(operation, variables, context)
            // Named so that timeout() does not derive a description from the operator chain on every call
            .name(subgraphName)
            .timeout(timeout, timer())
            .onErrorMap(TimeoutException.class,
                e -> new SubgraphTimeoutException(subgraphName, timeout, e));
    }

    @Override
    public Mono<List<ExecutionResult>> executeBatch(OperationDefinition operation,
                                                    List<Map<String, Object>> variableSets,
                                                    FeddiGatewayRequestContext context) {
        return delegate.executeBatch(operation, variableSets, context)
            // Named so that timeout() does not derive a description from the operator chain on every call
            .name(subgraphName)
            .timeout(timeout, timer())
            .onErrorMap(TimeoutException.class,
                e -> new SubgraphTimeoutException(subgraphName, timeout, e));
    }

    /** The event loop of the calling thread as a scheduler, or {@link Schedulers#parallel()} off an event loop. */
    static Scheduler timer() {
        EventExecutor loop = ThreadExecutorMap.currentExecutor();
        return loop == null ? Schedulers.parallel()
            : LOOP_TIMERS.computeIfAbsent(loop, l -> Schedulers.fromExecutorService(l, "feddi-subgraph-timeout"));
    }

    public String subgraphName() {
        return subgraphName;
    }

    public Duration timeout() {
        return timeout;
    }
}
