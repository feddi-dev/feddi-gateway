package dev.feddi.federation.app;

import dev.feddi.federation.extension.FeddiGatewayRequestContext;
import dev.feddi.federation.extension.SubgraphRequestHeaderCustomizer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test that verifies HTTP headers (Authorization, User-Agent)
 * are forwarded from the gateway to subgraph servers, and that an optional
 * SubgraphRequestHeaderCustomizer bean can add or override headers.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // See note on YamlIntegrationTest — pin the random port to 127.0.0.1.
    properties = {"server.address=127.0.0.1"}
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(HeaderForwardingIntegrationTest.TestConfig.class)
public class HeaderForwardingIntegrationTest {

    // Registers the bean only — carries no test-specific behavior itself, so there's
    // nothing here a test's Arrange step needs to reach back up for. Each test configures
    // ConfigurableTestHeaderCustomizer's actual behavior as the first thing it does,
    // right next to the request it then sends — same shared-resource-reconfigured-
    // per-test shape as subgraphServer.resetStubs() below.
    @TestConfiguration
    static class TestConfig {
        @Bean
        ConfigurableTestHeaderCustomizer testHeaderCustomizer() {
            return new ConfigurableTestHeaderCustomizer();
        }
    }

    static class ConfigurableTestHeaderCustomizer implements SubgraphRequestHeaderCustomizer {
        private volatile BiConsumer<HttpHeaders, FeddiGatewayRequestContext> behavior = (headers, context) -> { };

        void setBehavior(BiConsumer<HttpHeaders, FeddiGatewayRequestContext> behavior) {
            this.behavior = behavior;
        }

        @Override
        public void customize(HttpHeaders headers, String subgraphName, FeddiGatewayRequestContext context) {
            behavior.accept(headers, context);
        }
    }

    @LocalServerPort
    private int gatewayPort;

    @Autowired
    private AdminServer adminServer;

    @Autowired
    private ConfigurableTestHeaderCustomizer headerCustomizer;

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

    @BeforeEach
    void resetHeaderCustomizer() {
        // Test execution order isn't guaranteed, so without this a customizer behavior
        // set by one test could still be active when an unrelated test runs next.
        headerCustomizer.setBehavior((headers, context) -> { });
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

    @Test
    void customizerAddsNewHeader() {
        // Arrange
        headerCustomizer.setBehavior((headers, context) -> headers.set("X-Custom-Header", "injected-by-customizer"));
        subgraphServer.resetStubs();
        subgraphServer.stubFor(
            "{ hello }",
            Map.of(),
            Map.of("data", Map.of("hello", "world")),
            null,
            null
        );

        // Act
        @SuppressWarnings("unchecked")
        Map<String, Object> response = gatewayClient.post()
            .uri("/graphql")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("query", "{ hello }"))
            .exchangeToMono(r -> r.bodyToMono(Map.class))
            .block();

        // Assert
        assertNotNull(response);

        var requests = subgraphServer.getRecordedRequests();
        assertEquals(1, requests.size());

        var recordedHeaders = requests.getFirst().headers();
        assertEquals("injected-by-customizer", recordedHeaders.get("x-custom-header"),
            "SubgraphRequestHeaderCustomizer should be able to add a new header");
    }

    @Test
    void customizerOverridesForwardedHeader() {
        // Arrange
        headerCustomizer.setBehavior((headers, context) -> headers.set("User-Agent", "Customized/2.0"));
        subgraphServer.resetStubs();
        subgraphServer.stubFor(
            "{ hello }",
            Map.of(),
            Map.of("data", Map.of("hello", "world")),
            null,
            null
        );

        // Act — the forwarded User-Agent below is whatever the customizer overrides it to.
        @SuppressWarnings("unchecked")
        Map<String, Object> response = gatewayClient.post()
            .uri("/graphql")
            .contentType(MediaType.APPLICATION_JSON)
            .header("User-Agent", "TestClient/1.0")
            .bodyValue(Map.of("query", "{ hello }"))
            .exchangeToMono(r -> r.bodyToMono(Map.class))
            .block();

        // Assert
        assertNotNull(response);

        var requests = subgraphServer.getRecordedRequests();
        assertEquals(1, requests.size());

        var recordedHeaders = requests.getFirst().headers();
        assertEquals("Customized/2.0", recordedHeaders.get("user-agent"),
            "SubgraphRequestHeaderCustomizer should be able to override an already-forwarded header");
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
