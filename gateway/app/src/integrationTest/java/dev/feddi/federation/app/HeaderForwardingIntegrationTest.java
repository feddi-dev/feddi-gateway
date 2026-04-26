package dev.feddi.federation.app;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test that verifies HTTP headers (Authorization, User-Agent)
 * are forwarded from the gateway to subgraph servers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class HeaderForwardingIntegrationTest {

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

        // Start a subgraph server
        subgraphServer = new GraphQLSubgraphServer("test", SUBGRAPH_SDL);
        subgraphServer.start();

        // Upload schema to gateway
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
    void authorizationAndUserAgentHeadersAreForwardedToSubgraph() {
        // Configure stub
        subgraphServer.resetStubs();
        subgraphServer.stubFor(
            "{ hello }",
            Map.of(),
            Map.of("data", Map.of("hello", "world")),
            null,
            null
        );

        // Send request with Authorization and User-Agent headers
        @SuppressWarnings("unchecked")
        Map<String, Object> response = gatewayClient.post()
            .uri("/graphql")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Authorization", "Bearer test-token-123")
            .header("User-Agent", "TestClient/1.0")
            .bodyValue(Map.of("query", "{ hello }"))
            .exchangeToMono(r -> r.bodyToMono(Map.class))
            .block();

        // Verify the response
        assertNotNull(response);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertNotNull(data);
        assertEquals("world", data.get("hello"));

        // Verify headers were forwarded to the subgraph
        var requests = subgraphServer.getRecordedRequests();
        assertEquals(1, requests.size(), "Expected exactly one request to the subgraph");

        var recordedHeaders = requests.getFirst().headers();
        assertEquals("Bearer test-token-123", recordedHeaders.get("authorization"),
            "Authorization header should be forwarded to subgraph");
        assertEquals("TestClient/1.0", recordedHeaders.get("user-agent"),
            "User-Agent header should be forwarded to subgraph");
    }

    @Test
    void headersAreNotForwardedWhenNotPresent() {
        // Configure stub
        subgraphServer.resetStubs();
        subgraphServer.stubFor(
            "{ hello }",
            Map.of(),
            Map.of("data", Map.of("hello", "world")),
            null,
            null
        );

        // Send request without Authorization or custom User-Agent
        @SuppressWarnings("unchecked")
        Map<String, Object> response = gatewayClient.post()
            .uri("/graphql")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("query", "{ hello }"))
            .exchangeToMono(r -> r.bodyToMono(Map.class))
            .block();

        assertNotNull(response);

        var requests = subgraphServer.getRecordedRequests();
        assertEquals(1, requests.size());

        var recordedHeaders = requests.getFirst().headers();
        // Authorization should not be present since we didn't send it
        assertTrue(recordedHeaders.get("authorization") == null
            || recordedHeaders.get("authorization").isEmpty(),
            "Authorization header should not be present when not sent");
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
                        .map(body -> {
                            throw new RuntimeException("Upload failed: " + r.statusCode() + ": " + body);
                        });
                }
                return r.bodyToMono(Map.class);
            })
            .block();

        assertNotNull(uploadResponse);
        assertEquals(true, uploadResponse.get("success"), "Schema upload should succeed");
    }
}
