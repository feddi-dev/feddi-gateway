package dev.feddi.federation.app;

import dev.feddi.federation.engine.executor.SubgraphTimeoutException;
import dev.feddi.federation.extension.FeddiGatewayRequestContext;
import dev.feddi.federation.extension.SubgraphClient;
import dev.feddi.federation.extension.SubgraphSettings;
import graphql.language.OperationDefinition;
import graphql.parser.Parser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** Subgraph calls made on an event loop, sent over plain-Netty connections of that loop. */
class LoopConnectionsTest {

    private static final OperationDefinition QUERY = Parser.parse("{ ok }")
        .getDefinitionsOfType(OperationDefinition.class).getFirst();
    private static final String OK = "{\"data\":{\"ok\":true}}";

    private final AtomicInteger connections = new AtomicInteger();
    private DisposableServer subgraph;
    private DisposableServer gateway;

    @AfterEach
    void stop() {
        if (gateway != null) {
            gateway.disposeNow();
        }
        if (subgraph != null) {
            subgraph.disposeNow();
        }
    }

    @Test
    void callsWaitForAConnectionOfTheirLoop() {
        startSubgraph(Duration.ofMillis(20), Map.of(), OK, null);
        SubgraphClient client = client(Map.of("maxConnections", 1));
        startGateway(client, call -> call);

        List<String> answers = callGateway(100, 32);

        assertThat(answers).hasSize(100).containsOnly(OK);
        assertThat(connections.get()).as("connections").isLessThanOrEqualTo(availableLoops());
    }

    @Test
    void retriesOnANewConnectionWhenTheSubgraphClosedAnIdleOne() throws Exception {
        startSubgraph(Duration.ZERO, Map.of(), OK, Duration.ofMillis(100));
        SubgraphClient client = client(Map.of("maxConnections", 1));
        startGateway(client, call -> call);

        assertThat(callGateway(1, 1)).containsExactly(OK);
        Thread.sleep(400);
        assertThat(callGateway(1, 1)).containsExactly(OK);
        assertThat(connections.get()).as("connections").isEqualTo(2);
    }

    @Test
    void opensANewConnectionWhenTheSubgraphAsksToClose() {
        startSubgraph(Duration.ZERO, Map.of("Connection", "close"), OK, null);
        startGateway(client(Map.of()), call -> call);

        assertThat(callGateway(5, 1)).hasSize(5).containsOnly(OK);
        assertThat(connections.get()).as("connections").isEqualTo(5);
    }

    @Test
    void failsAResponseLargerThanTheMaximum() {
        startSubgraph(Duration.ZERO, Map.of(), OK, null);
        startGateway(client(Map.of("maxResponseBytes", 10L)), call -> call);

        assertThat(callGateway(1, 1)).singleElement().asString().contains("maxResponseBytes");
    }

    @Test
    void aTimedOutCallClosesItsConnectionAndLaterCallsSucceed() {
        AtomicInteger requests = new AtomicInteger();
        subgraph = HttpServer.create().port(0)
            .doOnChannelInit((observer, channel, address) -> connections.incrementAndGet())
            .handle((request, response) -> request.receive().then(Mono.delay(
                    requests.getAndIncrement() == 0 ? Duration.ofSeconds(2) : Duration.ZERO))
                .then(response.header("Content-Type", "application/json").sendString(Mono.just(OK)).then()))
            .bindNow();
        SubgraphClient client = new TimeoutAwareSubgraphClient(client(Map.of("maxConnections", 1)), "catalog",
            Duration.ofMillis(200));
        startGateway(client, call -> call);

        assertThat(callGateway(1, 1)).singleElement().asString().contains(SubgraphTimeoutException.class.getSimpleName());
        assertThat(callGateway(1, 1)).containsExactly(OK);
    }

    private void startSubgraph(Duration delay, Map<String, String> headers, String body, Duration idleTimeout) {
        HttpServer server = HttpServer.create().port(0)
            .doOnChannelInit((observer, channel, address) -> connections.incrementAndGet())
            .handle((request, response) -> {
                headers.forEach(response::header);
                return request.receive().then(Mono.delay(delay))
                    .then(response.header("Content-Type", "application/json").sendString(Mono.just(body)).then());
            });
        subgraph = (idleTimeout != null ? server.idleTimeout(idleTimeout) : server).bindNow();
    }

    private SubgraphClient client(Map<String, Object> settings) {
        Map<String, Object> config = new HashMap<>(settings);
        config.put("url", "http://localhost:" + subgraph.port() + "/graphql");
        return new DefaultSubgraphClientFactory(null).create("catalog", new SubgraphSettings(config));
    }

    /** A gateway that answers each request with the subgraph's body, or the call's error, from its event loop. */
    private void startGateway(SubgraphClient client, Function<Mono<String>, Mono<String>> call) {
        gateway = HttpServer.create().port(0)
            .handle((request, response) -> response.sendString(call.apply(
                client.execute(QUERY, Map.of(), FeddiGatewayRequestContext.empty())
                    .map(result -> OK)
                    .onErrorResume(e -> Mono.just(e.getClass().getSimpleName() + ": " + e.getMessage())))))
            .bindNow();
    }

    private List<String> callGateway(int calls, int concurrency) {
        HttpClient caller = HttpClient.create().port(gateway.port());
        return Flux.range(0, calls)
            .flatMap(i -> caller.get().uri("/").responseContent().aggregate().asString(), concurrency)
            .collectList()
            .block(Duration.ofSeconds(30));
    }

    private static int availableLoops() {
        return Math.max(4, 2 * Runtime.getRuntime().availableProcessors());
    }
}
