package dev.feddi.federation.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.feddi.federation.engine.executor.BatchingOptions;
import dev.feddi.federation.engine.executor.BatchingOptions.Mode;
import dev.feddi.federation.extension.FeddiGatewayDefinition;
import dev.feddi.federation.extension.FeddiGatewayRequestContext;
import dev.feddi.federation.extension.FeddiGatewaySettings;
import dev.feddi.federation.extension.SubgraphDefinition;
import dev.feddi.federation.extension.SubgraphSettings;
import graphql.ExecutionResult;
import graphql.language.OperationDefinition;
import graphql.parser.Parser;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultSubgraphClientBatchTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final OperationDefinition LOOKUP = Parser.parse("query ($id: ID!) { user(id: $id) { name } }")
        .getDefinitionsOfType(OperationDefinition.class).get(0);

    private final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
    private final List<Map<String, String>> requestHeaders = new CopyOnWriteArrayList<>();
    private DisposableServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.disposeNow();
        }
    }

    @Test
    void sendsVariablesArrayAndOrdersJsonLinesByVariableIndex() throws Exception {
        start(body -> response(200, "application/jsonl", """
            {"data":{"user":{"name":"Bob"}},"variableIndex":1}
            {"data":{"user":{"name":"Ann"}},"variableIndex":0}
            """));

        List<ExecutionResult> results = client().executeBatch(LOOKUP,
            List.of(Map.of("id", "1"), Map.of("id", "2")), FeddiGatewayRequestContext.empty()).block();

        assertThat(results).extracting(r -> (Object) r.getData()).containsExactly(
            Map.of("user", Map.of("name", "Ann")), Map.of("user", Map.of("name", "Bob")));
        assertThat(requests).singleElement()
            .satisfies(r -> assertThat(r.get("variables")).isEqualTo(List.of(Map.of("id", "1"), Map.of("id", "2"))));
    }

    @Test
    void acceptsJsonArrayResponse() {
        start(body -> response(200, "application/json", """
            [{"data":{"user":{"name":"Ann"}}},{"data":{"user":null},"errors":[{"message":"not found","path":["user"]}]}]
            """));

        List<ExecutionResult> results = client().executeBatch(LOOKUP,
            List.of(Map.of("id", "1"), Map.of("id", "2")), FeddiGatewayRequestContext.empty()).block();

        assertThat(results).hasSize(2);
        assertThat(results.get(1).getErrors()).extracting(e -> e.getMessage()).containsExactly("not found");
    }

    @Test
    void singleResponseToBatchIsAnError() {
        start(body -> response(200, "application/json", "{\"data\":{\"user\":{\"name\":\"Ann\"}}}"));

        assertThatThrownBy(() -> client().executeBatch(LOOKUP,
            List.of(Map.of("id", "1"), Map.of("id", "2")), FeddiGatewayRequestContext.empty()).block())
            .hasMessageContaining("invalid variable batch response");
    }

    @Test
    void httpErrorIsAnError() {
        start(body -> response(400, "application/json", "{\"errors\":[{\"message\":\"variables must be an object\"}]}"));

        assertThatThrownBy(() -> client().executeBatch(LOOKUP,
            List.of(Map.of("id", "1"), Map.of("id", "2")), FeddiGatewayRequestContext.empty()).block())
            .hasMessageContaining("400");
    }

    @Test
    void probeDetectsSupportAndMissingSupport() {
        start(body -> response(200, "application/jsonl", """
            {"data":{"__typename":"Query"},"variableIndex":0}
            {"data":{"__typename":"Query"},"variableIndex":1}
            """));
        assertThat(client().supportsVariableBatching().block()).isTrue();
        server.disposeNow();

        start(body -> response(400, "application/json", "{\"errors\":[{\"message\":\"bad request\"}]}"));
        assertThat(client().supportsVariableBatching().block()).isFalse();
    }

    @Test
    void batchForwardsAuthorizationAndUserAgent() {
        start(body -> response(200, "application/jsonl", "{\"data\":{\"user\":null},\"variableIndex\":0}"));
        var context = FeddiGatewayRequestContext.builder(Map.of("Authorization", "Bearer t", "User-Agent", "k6")).build();

        client().executeBatch(LOOKUP, List.of(Map.of("id", "1")), context).block();

        assertThat(requestHeaders).singleElement().satisfies(h -> assertThat(h)
            .containsEntry("authorization", "Bearer t")
            .containsEntry("user-agent", "k6")
            .containsEntry("accept", "application/jsonl, application/json"));
    }

    @Test
    void probeTreatsInvalidSuccessfulResponseAsUnsupported() {
        start(body -> response(200, "text/html", "<html>not graphql</html>"));

        assertThat(client().supportsVariableBatching().block()).isFalse();
    }

    @Test
    void reloadKeepsVariableBatchingWhenSubgraphIsUnreachable() throws Exception {
        start(body -> response(200, "application/json", "{}"));
        String unreachable = url();
        server.disposeNow();
        server = null;
        var holder = new FeddiGatewayHolder();
        var service = new FeddiGatewayReloadService(holder,
            new DefaultSubgraphClientFactory(WebClient.builder()),
            new FeddiGatewayMetrics(new SimpleMeterRegistry()), null, new FeddiGatewayConfigFile());

        service.reload(new FeddiGatewayDefinition(Map.of("accounts", new SubgraphDefinition(
            "type Query { user(id: ID!): User } type User { id: ID! name: String }",
            new SubgraphSettings(Map.of("url", unreachable, "batching", "variables")))),
            FeddiGatewaySettings.defaults()));
        Thread.sleep(1_000);

        assertThat(holder.get().batching("accounts").mode()).isEqualTo(Mode.VARIABLES);
    }

    @Test
    void parseRejectsMissingOrInvalidIndexes() {
        assertThatThrownBy(() -> DefaultSubgraphClient.parseBatchResponse("{\"data\":{}}", 1))
            .hasMessageContaining("variableIndex");
        assertThatThrownBy(() -> DefaultSubgraphClient.parseBatchResponse("{\"data\":{},\"variableIndex\":0}", 2))
            .hasMessageContaining("missing results");
        assertThatThrownBy(() -> DefaultSubgraphClient.parseBatchResponse("not json", 1))
            .hasMessageContaining("not JSON");
    }

    @Test
    void reloadFallsBackToAliasWhenSubgraphLacksVariableBatching() throws Exception {
        start(body -> response(400, "application/json", "{\"errors\":[{\"message\":\"bad request\"}]}"));
        var holder = new FeddiGatewayHolder();
        var service = new FeddiGatewayReloadService(holder,
            new DefaultSubgraphClientFactory(WebClient.builder()),
            new FeddiGatewayMetrics(new SimpleMeterRegistry()), null, new FeddiGatewayConfigFile());

        service.reload(new FeddiGatewayDefinition(Map.of("accounts", new SubgraphDefinition(
            "type Query { user(id: ID!): User } type User { id: ID! name: String }",
            new SubgraphSettings(Map.of("url", url(), "batching", "variables", "batch-max-size", 8)))),
            FeddiGatewaySettings.defaults()));

        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (holder.get().batching("accounts").mode() != Mode.ALIAS && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(holder.get().batching("accounts")).isEqualTo(new BatchingOptions(Mode.ALIAS, 8));
    }

    private record Response(int status, String contentType, String body) {
    }

    private static Response response(int status, String contentType, String body) {
        return new Response(status, contentType, body);
    }

    private void start(Function<String, Response> handler) {
        server = HttpServer.create().port(0)
            .route(routes -> routes.post("/graphql", (request, response) -> request.receive().aggregate().asString()
                .flatMap(body -> {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> parsed = JSON.readValue(body, Map.class);
                        requests.add(parsed);
                    } catch (Exception e) {
                        return Mono.error(e);
                    }
                    Map<String, String> headers = new HashMap<>();
                    request.requestHeaders().forEach(h -> headers.put(h.getKey().toLowerCase(), h.getValue()));
                    requestHeaders.add(headers);
                    Response r = handler.apply(body);
                    return response.status(HttpResponseStatus.valueOf(r.status()))
                        .header("Content-Type", r.contentType())
                        .sendString(Mono.just(r.body()))
                        .then();
                })))
            .bindNow();
    }

    private String url() {
        return "http://localhost:" + server.port() + "/graphql";
    }

    private DefaultSubgraphClient client() {
        return new DefaultSubgraphClient(WebClient.builder().baseUrl(url()).build(), "accounts");
    }
}
