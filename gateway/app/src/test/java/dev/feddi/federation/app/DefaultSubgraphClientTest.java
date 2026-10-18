package dev.feddi.federation.app;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import dev.feddi.federation.engine.executor.RawString;
import dev.feddi.federation.extension.FeddiGatewayRequestContext;
import graphql.ExecutionResult;
import graphql.GraphQLError;
import graphql.language.OperationDefinition;
import graphql.parser.Parser;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Single requests of {@link DefaultSubgraphClient}: how it reads subgraph responses and subgraph URLs. */
class DefaultSubgraphClientTest {

    private static final OperationDefinition QUERY = Parser.parse("query ($id: ID!) { user(id: $id) { name } }")
        .getDefinitionsOfType(OperationDefinition.class).get(0);

    private final List<String> requestUris = new CopyOnWriteArrayList<>();
    private DisposableServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.disposeNow();
        }
    }

    @Test
    void readsDataAndErrors() {
        start(200, """
            {"data":{"user":null},"errors":[{"message":"not found","path":["user"],"extensions":{"code":"NF"}},{}]}
            """);

        ExecutionResult result = execute(Map.of("id", "1"));

        assertThat(RawString.decodeAll(result.<Object>getData())).isEqualTo(Collections.singletonMap("user", null));
        assertThat(result.getErrors()).extracting(GraphQLError::getMessage)
            .containsExactly("not found", "Subgraph accounts returned an invalid error: {}");
        assertThat(result.getErrors().get(0).getPath()).containsExactly("user");
        assertThat(result.getErrors().get(0).getExtensions()).containsEntry("code", "NF");
    }

    @Test
    void entriesThatAreNoErrorsAreInvalidErrorsOfTheSubgraph() {
        start(200, """
            {"data":{"user":null},"errors":["text",{"message":1,"path":["user"]},{"message":"m","path":"user","extensions":[]}]}
            """);

        ExecutionResult result = execute(Map.of());

        assertThat(result.getErrors()).extracting(GraphQLError::getMessage).containsExactly(
            "Subgraph accounts returned an invalid error: \"text\"",
            "Subgraph accounts returned an invalid error: {\"message\":1,\"path\":[\"user\"]}",
            "m");
        assertThat(result.getErrors().get(1).getPath()).containsExactly("user");
        assertThat(result.getErrors().get(2).getPath()).isNull();
        assertThat(result.getErrors().get(2).getExtensions()).isNull();
    }

    @Test
    void emptySuccessfulResponseIsEmptyData() {
        start(200, "");

        assertThat(execute(null).<Object>getData()).isEqualTo(Map.of());
    }

    @Test
    void emptyErrorResponseIsAnError() {
        start(502, "");

        assertThatThrownBy(() -> execute(Map.of())).hasMessageContaining("Subgraph call failed: 502");
    }

    @Test
    void invalidJsonIsAnError() {
        start(200, "{\"data\":");

        assertThatThrownBy(() -> execute(Map.of())).hasMessageContaining("returned invalid JSON");
    }

    @Test
    void invalidJsonErrorResponseReportsTheStatus() {
        start(503, "<html>unavailable</html>");

        assertThatThrownBy(() -> execute(Map.of())).hasMessageContaining("Subgraph call failed: 503");
    }

    @Test
    void responseThatIsNoObjectIsAnError() {
        start(200, "[1]");

        assertThatThrownBy(() -> execute(Map.of())).hasMessageContaining("returned no JSON object");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"errors\":[{\"message\":\"bad variables\"}]}",
        "{\"errors\":[]}",
        "{\"errors\":[\"text\"]}",
        "{\"errors\":[{}]}"})
    void errorResponseIsAnErrorWithTheFirstMessage(String body) {
        start(400, body);

        assertThatThrownBy(() -> execute(Map.of()))
            .hasMessage("Subgraph call failed: " + (body.contains("bad variables") ? "bad variables" : "Unknown error"));
    }

    @Test
    void logsQueriesAndVariablesAtDebugLevel() {
        Logger logger = (Logger) LoggerFactory.getLogger(DefaultSubgraphClient.class);
        Level level = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        try {
            start(200, """
                {"data":{"user":{"name":"Ann"}},"errors":[{"message":"partial"}]}
                """);
            assertThat(execute(Map.of("id", "1")).getErrors()).hasSize(1);
            assertThat(execute(Map.of()).getErrors()).hasSize(1);

            server.disposeNow();
            start(200, "[{\"data\":{}},{\"data\":{}}]");
            assertThat(client(url()).executeBatch(QUERY, List.of(Map.of("id", "1"), Map.of("id", "2")),
                FeddiGatewayRequestContext.empty()).block()).hasSize(2);
        } finally {
            logger.setLevel(level);
        }
    }

    @Test
    void sendsThePathAndQueryOfTheUrl() {
        start(200, "{\"data\":{}}");

        client("http://localhost:" + server.port() + "/graphql?tenant=a%20b")
            .execute(QUERY, Map.of(), FeddiGatewayRequestContext.empty()).block();
        client("http://localhost:" + server.port())
            .execute(QUERY, Map.of(), FeddiGatewayRequestContext.empty()).block();

        assertThat(requestUris).containsExactly("/graphql?tenant=a%20b", "/");
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost:4001/graphql", "ftp://localhost/graphql", "http:///graphql"})
    void rejectsUrlsWithoutHttpSchemeOrHost(String url) {
        assertThatThrownBy(() -> client(url))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("accounts");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://subgraph.example/graphql", "https://subgraph.example:8443/graphql",
        "http://[::1]/graphql", "http://[::1]:4001/graphql"})
    void acceptsHttpsAndIpv6Urls(String url) {
        assertThat(client(url)).isNotNull();
    }

    private ExecutionResult execute(Map<String, Object> variables) {
        return client(url()).execute(QUERY, variables, FeddiGatewayRequestContext.empty()).block();
    }

    private void start(int status, String body) {
        server = HttpServer.create().port(0)
            .handle((request, response) -> {
                requestUris.add(request.uri());
                return request.receive().aggregate().asString().defaultIfEmpty("")
                    .flatMap(received -> response.status(HttpResponseStatus.valueOf(status))
                        .header("Content-Type", "application/json")
                        .sendString(body.isEmpty() ? Mono.empty() : Mono.just(body))
                        .then());
            })
            .bindNow();
    }

    private String url() {
        return "http://localhost:" + server.port() + "/graphql";
    }

    private static DefaultSubgraphClient client(String url) {
        return new DefaultSubgraphClient(HttpClient.create(), url, "accounts", null);
    }
}
