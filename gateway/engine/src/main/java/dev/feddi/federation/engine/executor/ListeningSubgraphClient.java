package dev.feddi.federation.engine.executor;

import graphql.ExecutionResult;
import graphql.language.OperationDefinition;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.util.context.Context;

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
        // Timed from now: the executor subscribes to a call right after creating it
        return new Timed<>(delegate.execute(operation, variables), this, System.nanoTime());
    }

    @Override
    public Mono<List<ExecutionResult>> executeBatch(OperationDefinition operation,
                                                    List<Map<String, Object>> variableSets) {
        return new Timed<>(delegate.executeBatch(operation, variableSets), this, System.nanoTime());
    }

    @Override
    public BatchingOptions batching() {
        return delegate.batching();
    }

    @Override
    public boolean reusesResults() {
        return delegate.reusesResults();
    }

    /** A call reported to the listener when it answers: one operator, instead of {@code doOnNext} and {@code doOnError}. */
    private static final class Timed<T> extends Mono<T> {
        private final Mono<T> source;
        private final ListeningSubgraphClient client;
        private final long start;

        Timed(Mono<T> source, ListeningSubgraphClient client, long start) {
            this.source = source;
            this.client = client;
            this.start = start;
        }

        @Override
        public void subscribe(CoreSubscriber<? super T> actual) {
            source.subscribe(new TimedSubscriber<>(actual, this));
        }
    }

    private static final class TimedSubscriber<T> implements CoreSubscriber<T>, Subscription {
        private final CoreSubscriber<? super T> actual;
        private final Timed<T> call;
        private @Nullable Subscription upstream;

        TimedSubscriber(CoreSubscriber<? super T> actual, Timed<T> call) {
            this.actual = actual;
            this.call = call;
        }

        @Override
        public Context currentContext() {
            return actual.currentContext();
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            if (Operators.validate(upstream, subscription)) {
                upstream = subscription;
                actual.onSubscribe(this);
            }
        }

        @Override
        public void onNext(T value) {
            report(true);
            actual.onNext(value);
        }

        @Override
        public void onError(Throwable error) {
            report(false);
            actual.onError(error);
        }

        @Override
        public void onComplete() {
            actual.onComplete();
        }

        private void report(boolean success) {
            ListeningSubgraphClient client = call.client;
            client.listener.onSubgraphFetchComplete(client.subgraphName, System.nanoTime() - call.start, success);
        }

        @Override
        public void request(long n) {
            upstream.request(n);
        }

        @Override
        public void cancel() {
            upstream.cancel();
        }
    }
}
