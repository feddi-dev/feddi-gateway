package dev.feddi.federation.engine.executor;

import org.jspecify.annotations.Nullable;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.util.context.Context;

import java.util.function.Function;

/**
 * Maps a call's value or its error to a value: one operator, instead of {@code map} and {@code onErrorResume}
 * (and the maps after them). An exception thrown by either function fails the result, as in {@code map}.
 */
final class FoldMono<T, R> extends Mono<R> {

    private final Mono<T> source;
    private final Function<? super T, ? extends R> onValue;
    private final Function<? super Throwable, ? extends R> onError;

    FoldMono(Mono<T> source, Function<? super T, ? extends R> onValue,
             Function<? super Throwable, ? extends R> onError) {
        this.source = source;
        this.onValue = onValue;
        this.onError = onError;
    }

    @Override
    public void subscribe(CoreSubscriber<? super R> actual) {
        source.subscribe(new FoldSubscriber<>(actual, this));
    }

    private static final class FoldSubscriber<T, R> implements CoreSubscriber<T>, Subscription {
        private final CoreSubscriber<? super R> actual;
        private final FoldMono<T, R> fold;
        private @Nullable Subscription upstream;
        private boolean done;

        FoldSubscriber(CoreSubscriber<? super R> actual, FoldMono<T, R> fold) {
            this.actual = actual;
            this.fold = fold;
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
            if (done) {
                Operators.onNextDropped(value, actual.currentContext());
                return;
            }
            done = true;
            R result;
            try {
                result = fold.onValue.apply(value);
            } catch (Throwable e) {
                actual.onError(Operators.onOperatorError(upstream, e, value, actual.currentContext()));
                return;
            }
            actual.onNext(result);
            actual.onComplete();
        }

        @Override
        public void onError(Throwable error) {
            if (done) {
                Operators.onErrorDropped(error, actual.currentContext());
                return;
            }
            done = true;
            recover(error);
        }

        private void recover(Throwable error) {
            R result;
            try {
                result = fold.onError.apply(error);
            } catch (Throwable e) {
                actual.onError(Operators.onOperatorError(e, actual.currentContext()));
                return;
            }
            actual.onNext(result);
            actual.onComplete();
        }

        @Override
        public void onComplete() {
            if (!done) {
                done = true;
                actual.onComplete();
            }
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
