package dev.feddi.federation.app;

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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Subgraph calls made on an event loop use that loop's connection pool. */
class DefaultSubgraphClientLoopTest {

    private static final OperationDefinition QUERY = Parser.parse("{ ok }")
        .getDefinitionsOfType(OperationDefinition.class).getFirst();

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
    void readsTheResponseOnTheLoopOfTheCallingRequest() {
        subgraph = HttpServer.create().port(0)
            .handle((request, response) -> request.receive().then(
                response.header("Content-Type", "application/json").sendString(Mono.just("{\"data\":{\"ok\":true}}"))
                    .then()))
            .bindNow();
        SubgraphClient client = new DefaultSubgraphClientFactory(null).create("catalog",
            new SubgraphSettings(Map.of("url", "http://localhost:" + subgraph.port() + "/graphql")));
        // Answers whether the subgraph's result arrived on the thread that handles the request
        gateway = HttpServer.create().port(0)
            .handle((request, response) -> {
                Thread handler = Thread.currentThread();
                return response.sendString(client.execute(QUERY, Map.of(), FeddiGatewayRequestContext.empty())
                    .map(result -> Thread.currentThread() == handler ? "same" : "other"));
            })
            .bindNow();

        HttpClient caller = HttpClient.create().port(gateway.port());
        List<String> answers = Flux.range(0, 400)
            .flatMap(i -> caller.get().uri("/").responseContent().aggregate().asString(), 32)
            .collectList()
            .block(Duration.ofSeconds(30));

        assertThat(answers).hasSize(400).containsOnly("same");
    }
}
