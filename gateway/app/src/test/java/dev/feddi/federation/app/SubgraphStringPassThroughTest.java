package dev.feddi.federation.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.feddi.federation.engine.compose.Composer.SubgraphInput;
import dev.feddi.federation.engine.executor.BatchingOptions;
import dev.feddi.federation.extension.SubgraphClient;
import graphql.ExecutionInput;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Strings from subgraph responses pass through the gateway as JSON text (escapes included) and are decoded where
 * the gateway needs their values: entity keys sent to another subgraph and error messages. Two subgraphs over HTTP
 * answer with handwritten JSON, so the escapes are exactly as written here.
 */
class SubgraphStringPassThroughTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String PRODUCTS_SDL = """
        type Query { products: [Product] }
        type Product @key(fields: "upc") { upc: ID! name: String tags: [String] }
        """;

    private static final String REVIEWS_SDL = """
        type Query { productByUpc(upc: ID!): Product @lookup @internal }
        type Product @key(fields: "upc") { upc: ID! note: String }
        """;

    /** Strings with escapes, non-ASCII characters (raw and escaped), an empty and a long string. */
    private static final String PRODUCTS_RESPONSE = """
        {"data":{"products":[
          {"upc":"a\\"b","name":"Caf\\u00e9 \\"Oak\\" \\\\ \\/ \\t ä € \\uD83D\\uDE00 😀","tags":["x\\ny","","\\u0041"]},
          {"upc":"plain","name":"%s","tags":null}
        ]}}
        """.formatted("Lorem ipsum dolor sit amet, ".repeat(40));

    /** JSON lines in reverse order, one with an error whose message and path have escapes. */
    private static final String REVIEWS_RESPONSE = """
        {"data":{"productByUpc":{"note":"n\\u00f6te \\"2\\""}},"variableIndex":1}
        {"data":{"productByUpc":{"note":null}},"errors":[{"message":"no \\"note\\" for a\\\\b","path":["productByUpc","note"]}],"variableIndex":0}
        """;

    private final List<DisposableServer> servers = new ArrayList<>();
    private final List<Map<String, Object>> reviewRequests = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopServers() {
        servers.forEach(DisposableServer::disposeNow);
    }

    @Test
    void stringsPassThroughAsWrittenAndAreDecodedWhereNeeded() throws Exception {
        String productsUrl = start(PRODUCTS_RESPONSE, "application/json", null);
        String reviewsUrl = start(REVIEWS_RESPONSE, "application/jsonl", reviewRequests);
        Map<String, SubgraphClient> clients = new LinkedHashMap<>();
        clients.put("products", new DefaultSubgraphClient(HttpClient.create(), productsUrl, "products", null));
        clients.put("reviews", new DefaultSubgraphClient(HttpClient.create(), reviewsUrl, "reviews", null));
        FeddiFederationGateway gateway = FeddiFederationGateway.create(
                List.of(new SubgraphInput("products", productsUrl, PRODUCTS_SDL),
                    new SubgraphInput("reviews", reviewsUrl, REVIEWS_SDL)),
                clients)
            .withSubgraphBatching(Map.of("reviews",
                new BatchingOptions(BatchingOptions.Mode.VARIABLES, BatchingOptions.DEFAULT_MAX_BATCH_SIZE)));

        FeddiFederationGateway.GatewayResult result = gateway.execute(ExecutionInput.newExecutionInput()
            .query("{ products { upc name tags note } }").build()).block();
        byte[] json = result.toJson().json();

        // Valid JSON with the decoded values the subgraphs meant
        @SuppressWarnings("unchecked")
        Map<String, Object> response = JSON.readValue(json, Map.class);
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("upc", "a\"b");
        first.put("name", "Café \"Oak\" \\ / \t ä € 😀 😀");
        first.put("tags", List.of("x\ny", "", "A"));
        first.put("note", null);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("upc", "plain");
        second.put("name", "Lorem ipsum dolor sit amet, ".repeat(40));
        second.put("tags", null);
        second.put("note", "nöte \"2\"");
        assertThat(response.get("data")).isEqualTo(Map.of("products", Arrays.asList(first, second)));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) response.get("errors");
        assertThat(errors).singleElement()
            .satisfies(error -> assertThat(error).containsEntry("message", "no \"note\" for a\\b"));

        // Strings pass through as the subgraph wrote them
        String text = new String(json, StandardCharsets.UTF_8);
        assertThat(text).contains("\"Caf\\u00e9 \\\"Oak\\\" \\\\ \\/ \\t ä € \\uD83D\\uDE00 😀\"", "\"n\\u00f6te \\\"2\\\"\"");

        // Entity keys reach the other subgraph decoded (JSON-encoded again in its request)
        assertThat(reviewRequests).singleElement()
            .satisfies(request -> assertThat(request.get("variables"))
                .isEqualTo(List.of(Map.of("upc", "a\"b"), Map.of("upc", "plain"))));

        // The maps of the completed result hold Strings
        assertThat(result.executionResult().<Map<String, Object>>getData())
            .isEqualTo(Map.of("products", Arrays.asList(first, second)));
    }

    private String start(String body, String contentType, List<Map<String, Object>> requests) {
        DisposableServer server = HttpServer.create().port(0)
            .route(routes -> routes.post("/graphql", (request, response) -> request.receive().aggregate().asString()
                .flatMap(received -> {
                    if (requests != null) {
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> parsed = JSON.readValue(received, Map.class);
                            requests.add(parsed);
                        } catch (Exception e) {
                            return Mono.error(e);
                        }
                    }
                    return response.status(HttpResponseStatus.OK)
                        .header("Content-Type", contentType)
                        .sendByteArray(Mono.just(body.getBytes(StandardCharsets.UTF_8)))
                        .then();
                })))
            .bindNow();
        servers.add(server);
        return "http://localhost:" + server.port() + "/graphql";
    }
}
