package dev.feddi.federation.app;

import dev.feddi.federation.extension.FeddiGatewayRequestContext;
import dev.feddi.federation.extension.SubgraphClient;
import dev.feddi.federation.engine.executor.SubgraphTimeoutException;
import graphql.ExecutionResult;
import graphql.language.OperationDefinition;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.internal.ThreadExecutorMap;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

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
        return new TimeoutMono<>(delegate.execute(operation, variables, context), this);
    }

    @Override
    public Mono<List<ExecutionResult>> executeBatch(OperationDefinition operation,
                                                    List<Map<String, Object>> variableSets,
                                                    FeddiGatewayRequestContext context) {
        return new TimeoutMono<>(delegate.executeBatch(operation, variableSets, context), this);
    }

    /** The event loop of the calling thread as a scheduler, or {@link Schedulers#parallel()} off an event loop. */
    static Scheduler timer() {
        EventExecutor loop = ThreadExecutorMap.currentExecutor();
        return loop == null ? Schedulers.parallel()
            : LOOP_TIMERS.computeIfAbsent(loop, l -> Schedulers.fromExecutorService(l, "feddi-subgraph-timeout"));
    }

    /**
     * A call that fails with a {@link SubgraphTimeoutException} when it has not answered in time: one operator per
     * call, instead of {@code name}, {@code timeout} (with its own timer subscriber) and {@code onErrorMap}.
     */
    private static final class TimeoutMono<T> extends Mono<T> {
        private final Mono<T> source;
        private final TimeoutAwareSubgraphClient client;

        TimeoutMono(Mono<T> source, TimeoutAwareSubgraphClient client) {
            this.source = source;
            this.client = client;
        }

        @Override
        public void subscribe(CoreSubscriber<? super T> actual) {
            source.subscribe(new TimeoutSubscriber<>(actual, client));
        }
    }

    private static final class TimeoutSubscriber<T> extends AtomicInteger implements CoreSubscriber<T>, Subscription {
        private static final int RUNNING = 0;
        private static final int ANSWERED = 1;
        private static final int TIMED_OUT = 2;

        private final CoreSubscriber<? super T> actual;
        private final TimeoutAwareSubgraphClient client;
        private @Nullable Subscription upstream;
        private @Nullable Disposable timer;

        TimeoutSubscriber(CoreSubscriber<? super T> actual, TimeoutAwareSubgraphClient client) {
            this.actual = actual;
            this.client = client;
        }

        @Override
        public Context currentContext() {
            return actual.currentContext();
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            if (Operators.validate(upstream, subscription)) {
                upstream = subscription;
                timer = timer().schedule(this::timeOut, client.timeout.toNanos(), TimeUnit.NANOSECONDS);
                actual.onSubscribe(this);
            }
        }

        private void timeOut() {
            if (compareAndSet(RUNNING, TIMED_OUT)) {
                upstream.cancel();
                actual.onError(new SubgraphTimeoutException(client.subgraphName, client.timeout,
                    new TimeoutException("Did not observe any item or terminal signal within "
                        + client.timeout.toMillis() + "ms in '" + client.subgraphName + "'")));
            }
        }

        private boolean answer() {
            if (compareAndSet(RUNNING, ANSWERED)) {
                timer.dispose();
                return true;
            }
            return get() == ANSWERED;
        }

        @Override
        public void onNext(T value) {
            if (get() == RUNNING ? answer() : get() == ANSWERED) {
                actual.onNext(value);
            } else {
                Operators.onNextDropped(value, actual.currentContext());
            }
        }

        @Override
        public void onError(Throwable error) {
            if (answer()) {
                actual.onError(error);
            } else {
                Operators.onErrorDropped(error, actual.currentContext());
            }
        }

        @Override
        public void onComplete() {
            if (answer()) {
                actual.onComplete();
            }
        }

        @Override
        public void request(long n) {
            upstream.request(n);
        }

        @Override
        public void cancel() {
            if (compareAndSet(RUNNING, ANSWERED)) {
                timer.dispose();
            }
            upstream.cancel();
        }
    }

    public String subgraphName() {
        return subgraphName;
    }

    public Duration timeout() {
        return timeout;
    }
}
