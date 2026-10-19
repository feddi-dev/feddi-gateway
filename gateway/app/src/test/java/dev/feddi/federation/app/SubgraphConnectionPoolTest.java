package dev.feddi.federation.app;

import dev.feddi.federation.extension.FeddiGatewayRequestContext;
import dev.feddi.federation.extension.SubgraphClient;
import dev.feddi.federation.extension.SubgraphSettings;
import graphql.language.OperationDefinition;
import graphql.parser.Parser;
import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The connection settings of the definition, as the built-in client factory applies them. */
class SubgraphConnectionPoolTest {

    private static final OperationDefinition QUERY = Parser.parse("{ hello }")
        .getDefinitionsOfType(OperationDefinition.class).get(0);

    private final AtomicInteger connections = new AtomicInteger();
    private DisposableServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.disposeNow();
        }
    }

    @Test
    void replacesConnectionsIdleLongerThanTheMaximum() throws Exception {
        start();
        SubgraphClient client = new DefaultSubgraphClientFactory(null)
            .create("accounts", settings(Map.of("maxIdleTimeMs", 100L)));

        call(client);
        int afterFirst = connections.get();
        Thread.sleep(500);
        call(client);

        assertThat(connections.get() - afterFirst).as("new connections").isEqualTo(1);
    }

    @Test
    void reusesConnectionsWithinTheMaximumIdleTime() throws Exception {
        start();
        SubgraphClient client = new DefaultSubgraphClientFactory(null)
            .create("accounts", settings(Map.of("maxIdleTimeMs", 10_000L)));

        call(client);
        int afterFirst = connections.get();
        Thread.sleep(500);
        call(client);

        assertThat(connections.get() - afterFirst).as("new connections").isZero();
    }

    @Test
    void givesUpConnectingAfterTheConnectTimeout() {
        // A non-routable address: connecting hangs until the timeout
        SubgraphClient client = new DefaultSubgraphClientFactory(null).create("accounts",
            new SubgraphSettings(Map.of("url", "http://10.255.255.1:81/graphql", "connectTimeoutMs", 300L)));

        long start = System.nanoTime();
        assertThatThrownBy(() -> call(client)).hasRootCauseInstanceOf(ConnectTimeoutException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    }

    private static void call(SubgraphClient client) {
        assertThat(client.execute(QUERY, Map.of(), FeddiGatewayRequestContext.empty()).block().<Object>getData())
            .isNotNull();
    }

    private SubgraphSettings settings(Map<String, Object> extra) {
        Map<String, Object> config = new java.util.HashMap<>(extra);
        config.put("url", "http://localhost:" + server.port() + "/graphql");
        return new SubgraphSettings(config);
    }

    private void start() {
        server = HttpServer.create().port(0)
            // Once per channel (doOnConnection is once per request)
            .doOnChannelInit((observer, channel, address) -> connections.incrementAndGet())
            .handle((request, response) -> request.receive().then(response.status(HttpResponseStatus.OK)
                .header("Content-Type", "application/json")
                .sendString(Mono.just("{\"data\":{\"hello\":\"world\"}}")).then()))
            .bindNow();
    }
}
