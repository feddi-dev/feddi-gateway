package dev.feddi.federation.app;

import dev.feddi.federation.engine.executor.SubgraphTimeoutException;
import dev.feddi.federation.extension.FeddiGatewayRequestContext;
import graphql.ExecutionResultImpl;
import graphql.language.OperationDefinition;
import graphql.parser.Parser;
import io.netty.channel.DefaultEventLoop;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TimeoutAwareSubgraphClientTest {

    private static final OperationDefinition OPERATION = Parser.parse("{ products { id } }")
        .getDefinitionsOfType(OperationDefinition.class).getFirst();

    private final DefaultEventLoop loop = new DefaultEventLoop();

    @AfterEach
    void shutDown() {
        loop.shutdownGracefully(0, 1, TimeUnit.SECONDS);
    }

    @Test
    void callStartedOnAnEventLoopTimesOutOnThatLoop() throws Exception {
        PerEntitySubgraphClient slow = (operation, variables, context) -> Mono.never();
        var client = new TimeoutAwareSubgraphClient(slow, "catalog", Duration.ofMillis(50));
        var signal = new CompletableFuture<Thread>();
        var error = new CompletableFuture<Throwable>();

        loop.execute(() -> client.execute(OPERATION, Map.of(), FeddiGatewayRequestContext.empty())
            .subscribe(result -> { }, e -> {
                signal.complete(Thread.currentThread());
                error.complete(e);
            }));

        assertThat(error.get(5, TimeUnit.SECONDS)).isInstanceOf(SubgraphTimeoutException.class);
        Thread loopThread = loop.submit(Thread::currentThread).get(5, TimeUnit.SECONDS);
        assertThat(signal.get()).isSameAs(loopThread);
    }

    @Test
    void callStartedOnAnEventLoopThatAnswersInTimeKeepsItsResult() throws Exception {
        PerEntitySubgraphClient fast = (operation, variables, context) -> Mono.just(
            ExecutionResultImpl.newExecutionResult().data(Map.of("ok", true)).build());
        var client = new TimeoutAwareSubgraphClient(fast, "catalog", Duration.ofMillis(50));
        var data = new CompletableFuture<Object>();

        loop.execute(() -> client.execute(OPERATION, Map.of(), FeddiGatewayRequestContext.empty())
            .subscribe(result -> data.complete(result.getData()), data::completeExceptionally));

        assertThat(data.get(5, TimeUnit.SECONDS)).isEqualTo(Map.of("ok", true));
    }

    @Test
    void timerOffAnEventLoopIsTheParallelScheduler() {
        assertThat(TimeoutAwareSubgraphClient.timer()).isSameAs(Schedulers.parallel());
    }

    @Test
    void timerOnAnEventLoopIsCachedPerLoop() throws Exception {
        var first = loop.submit(TimeoutAwareSubgraphClient::timer).get(5, TimeUnit.SECONDS);
        var second = loop.submit(TimeoutAwareSubgraphClient::timer).get(5, TimeUnit.SECONDS);

        assertThat(first).isSameAs(second).isNotSameAs(Schedulers.parallel());
    }
}
