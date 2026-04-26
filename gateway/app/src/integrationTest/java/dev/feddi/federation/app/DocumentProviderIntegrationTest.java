package dev.feddi.federation.app;

import dev.feddi.federation.customization.DocumentProvider;
import dev.feddi.federation.customization.GatewayRequestContext;
import graphql.ExecutionInput;
import graphql.GraphqlErrorBuilder;
import graphql.execution.preparsed.PreparsedDocumentEntry;
import graphql.language.Document;
import graphql.parser.Parser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the DocumentProvider extension point.
 * Verifies persisted query resolution, unknown hash errors, and fall-through to ParseAndValidate.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(DocumentProviderIntegrationTest.TestConfig.class)
public class DocumentProviderIntegrationTest {

    static final String KNOWN_QUERY = "{ hello }";
    static final String KNOWN_HASH = sha256Hex(KNOWN_QUERY);

    @TestConfiguration
    static class TestConfig {
        @Bean
        DocumentProvider testDocumentProvider() {
            var cache = new ConcurrentHashMap<String, Document>();
            cache.put(KNOWN_HASH, Parser.parse(KNOWN_QUERY));

            return (executionInput, context) -> {
                var ext = executionInput.getExtensions();
                if (ext != null && ext.get("persistedQuery") instanceof Map<?, ?> pq) {
                    if (pq.get("sha256Hash") instanceof String hash) {
                        var doc = cache.get(hash);
                        if (doc != null) {
                            return Mono.just(new PreparsedDocumentEntry(doc));
                        }
                        return Mono.just(new PreparsedDocumentEntry(
                                List.of(GraphqlErrorBuilder.newError()
                                        .message("PersistedQueryNotFound").build())));
                    }
                }
                return Mono.empty();
            };
        }
    }

    @LocalServerPort
    private int gatewayPort;

    @Autowired
    private AdminServer adminServer;

    private WebClient gatewayClient;
    private GraphQLSubgraphServer subgraphServer;

    private static final String SUBGRAPH_SDL = """
            type Query {
                hello: String
            }
            """;

    @BeforeAll
    void setup() {
        gatewayClient = WebClient.builder()
                .baseUrl("http://localhost:" + gatewayPort)
                .build();

        subgraphServer = new GraphQLSubgraphServer("test", SUBGRAPH_SDL);
        subgraphServer.start();

        Map<String, String> sdls = Map.of("test", SUBGRAPH_SDL);
        Map<String, String> urls = Map.of("test", subgraphServer.getUrl());
        byte[] zip = SchemaZipBuilder.createZip(sdls, urls);
        uploadSchemaToGateway(zip);
    }

    @AfterAll
    void teardown() {
        if (subgraphServer != null) {
            subgraphServer.stop();
        }
    }

    @Test
    void knownHashResolvesSuccessfully() {
        subgraphServer.resetStubs();
        subgraphServer.stubFor("{ hello }", Map.of(),
                Map.of("data", Map.of("hello", "world")), null, null);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("extensions", Map.of("persistedQuery", Map.of(
                "version", 1, "sha256Hash", KNOWN_HASH)));

        @SuppressWarnings("unchecked")
        Map<String, Object> response = gatewayClient.post()
                .uri("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToMono(r -> r.bodyToMono(Map.class))
                .block();

        assertNotNull(response);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertNotNull(data, "Response should have data: " + response);
        assertEquals("world", data.get("hello"));
    }

    @Test
    void unknownHashReturnsError() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("extensions", Map.of("persistedQuery", Map.of(
                "version", 1, "sha256Hash", "unknown-hash")));

        @SuppressWarnings("unchecked")
        Map<String, Object> response = gatewayClient.post()
                .uri("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToMono(r -> r.bodyToMono(Map.class))
                .block();

        assertNotNull(response);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) response.get("errors");
        assertNotNull(errors, "Response should have errors: " + response);
        assertFalse(errors.isEmpty());
        assertTrue(errors.getFirst().get("message").toString().contains("PersistedQueryNotFound"));
    }

    @Test
    void normalQueryFallsThroughToParseAndValidate() {
        subgraphServer.resetStubs();
        subgraphServer.stubFor("{ hello }", Map.of(),
                Map.of("data", Map.of("hello", "fallthrough")), null, null);

        @SuppressWarnings("unchecked")
        Map<String, Object> response = gatewayClient.post()
                .uri("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", "{ hello }"))
                .exchangeToMono(r -> r.bodyToMono(Map.class))
                .block();

        assertNotNull(response);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertNotNull(data, "Response should have data: " + response);
        assertEquals("fallthrough", data.get("hello"));
    }

    @Test
    void persistedQueryTakesPrecedenceOverQueryField() {
        subgraphServer.resetStubs();
        subgraphServer.stubFor("{ hello }", Map.of(),
                Map.of("data", Map.of("hello", "from-persisted")), null, null);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", "{ hello }");
        body.put("extensions", Map.of("persistedQuery", Map.of(
                "version", 1, "sha256Hash", KNOWN_HASH)));

        @SuppressWarnings("unchecked")
        Map<String, Object> response = gatewayClient.post()
                .uri("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToMono(r -> r.bodyToMono(Map.class))
                .block();

        assertNotNull(response);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertNotNull(data, "Response should have data: " + response);
        assertEquals("from-persisted", data.get("hello"));
    }

    private void uploadSchemaToGateway(byte[] zipBytes) {
        WebClient adminClient = WebClient.builder()
                .baseUrl("http://127.0.0.1:" + adminServer.getPort())
                .build();

        @SuppressWarnings("unchecked")
        Map<String, Object> uploadResponse = adminClient.post()
                .uri("/admin/upload")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue(zipBytes)
                .exchangeToMono(r -> {
                    if (r.statusCode().isError()) {
                        return r.bodyToMono(String.class)
                                .map(b -> {
                                    throw new RuntimeException("Upload failed: " + r.statusCode() + ": " + b);
                                });
                    }
                    return r.bodyToMono(Map.class);
                })
                .block();

        assertNotNull(uploadResponse);
        assertEquals(true, uploadResponse.get("success"));
    }

    private static String sha256Hex(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
