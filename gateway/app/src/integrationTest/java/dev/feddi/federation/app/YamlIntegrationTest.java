package dev.feddi.federation.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.feddi.federation.engine.testcase.ExecutionTest;
import dev.feddi.federation.engine.testcase.TestCaseLoader;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * YAML-based integration tests that run execution test scenarios using
 * WireMock servers as mock subgraphs.
 *
 * <p>This is an integration test because the gateway runs in the same JVM
 * as the test via {@code @SpringBootTest}. For true black-box e2e tests
 * with process isolation, see the {@code e2e-tests} project in the repository root.
 *
 * <p>For each schema directory:
 * <ol>
 *   <li>Starts WireMock servers for each subgraph defined in schema.yaml</li>
 *   <li>Creates a ZIP with schemas and config.yaml files pointing to WireMock URLs</li>
 *   <li>Uploads the ZIP to the gateway</li>
 *   <li>Runs each execution test YAML as a dynamic test</li>
 *   <li>Stops WireMock servers</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class YamlIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(YamlIntegrationTest.class);

    @LocalServerPort
    private int gatewayPort;

    @Autowired
    private AdminServer adminServer;

    private WebClient gatewayClient;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final TestCaseLoader testCaseLoader = new TestCaseLoader();

    @BeforeAll
    void setup() {
        gatewayClient = WebClient.builder()
            .baseUrl("http://localhost:" + gatewayPort)
            .build();
    }

    @TestFactory
    Stream<DynamicContainer> runAllExecutionTests() throws Exception {
        Path schemasDir = getSchemasDirectory();

        return Files.list(schemasDir)
            .filter(Files::isDirectory)
            .filter(dir -> Files.exists(dir.resolve("executions")))
            .sorted()
            .map(this::createSchemaTestContainer);
    }

    private Path getSchemasDirectory() throws URISyntaxException, IOException {
        // Try to find schemas directory from classpath
        var resource = getClass().getClassLoader().getResource("schemas");
        if (resource != null) {
            return Paths.get(resource.toURI());
        }
        throw new IOException("Could not find schemas directory in classpath");
    }

    private DynamicContainer createSchemaTestContainer(Path schemaDir) {
        String schemaName = schemaDir.getFileName().toString();

        List<DynamicNode> tests = new ArrayList<>();

        // Create a single server instance that's shared across all tests in this container
        DynamicSubgraphServers servers = new DynamicSubgraphServers();

        // Store context for re-uploads when tests have custom timeouts
        SchemaContext schemaContext = new SchemaContext();

        try {
            // Check if schema should be skipped
            if (isSchemaSkipped(schemaDir.resolve("schema.yaml"))) {
                tests.add(DynamicTest.dynamicTest("skipped", () -> {
                    org.junit.jupiter.api.Assumptions.assumeTrue(false, "Schema marked as skip: true");
                }));
                return DynamicContainer.dynamicContainer(schemaName, tests);
            }

            // Load schema YAML and extract subgraph SDLs
            Map<String, String> subgraphSdls = loadSubgraphSdls(schemaDir.resolve("schema.yaml"));
            schemaContext.subgraphSdls = subgraphSdls;

            // Setup test: start servers and upload schema
            tests.add(DynamicTest.dynamicTest("setup", () -> {
                servers.startServersForSubgraphs(subgraphSdls);
                schemaContext.subgraphUrls = servers.getSubgraphUrls();

                byte[] zip = SchemaZipBuilder.createZip(subgraphSdls, schemaContext.subgraphUrls);
                uploadSchemaToGateway(zip);
            }));

            // Load and add execution tests
            Path executionsDir = schemaDir.resolve("executions");
            if (Files.exists(executionsDir)) {
                Files.list(executionsDir)
                    .filter(p -> p.toString().endsWith(".yaml"))
                    .sorted()
                    .forEach(executionFile -> {
                        try {
                            ExecutionTest test = testCaseLoader.loadExecutionTest(executionFile);
                            tests.add(DynamicTest.dynamicTest(
                                test.name(),
                                () -> runExecutionTest(servers, schemaContext, test)
                            ));
                        } catch (IOException e) {
                            tests.add(DynamicTest.dynamicTest(
                                executionFile.getFileName().toString(),
                                () -> { throw new RuntimeException("Failed to load test: " + executionFile, e); }
                            ));
                        }
                    });
            }

            // Teardown test: stop servers
            tests.add(DynamicTest.dynamicTest("teardown", servers::stopAll));

        } catch (IOException e) {
            tests.add(DynamicTest.dynamicTest(
                "setup-failed",
                () -> { throw new RuntimeException("Failed to setup schema: " + schemaDir, e); }
            ));
        }

        return DynamicContainer.dynamicContainer(schemaName, tests);
    }

    /**
     * Holds schema context for re-uploads when tests have custom configurations.
     */
    private static class SchemaContext {
        Map<String, String> subgraphSdls;
        Map<String, String> subgraphUrls;
    }

    /**
     * Checks if a schema YAML has skip: true.
     */
    private boolean isSchemaSkipped(Path schemaYamlPath) throws IOException {
        try (InputStream is = Files.newInputStream(schemaYamlPath)) {
            JsonNode root = yamlMapper.readTree(is);
            return root.path("skip").asBoolean(false);
        }
    }

    /**
     * Loads subgraph SDL strings from a schema.yaml file.
     */
    private Map<String, String> loadSubgraphSdls(Path schemaYamlPath) throws IOException {
        Map<String, String> sdls = new LinkedHashMap<>();

        try (InputStream is = Files.newInputStream(schemaYamlPath)) {
            JsonNode root = yamlMapper.readTree(is);
            JsonNode subgraphsNode = root.path("subgraphs");

            if (subgraphsNode.isObject()) {
                subgraphsNode.fields().forEachRemaining(entry -> {
                    sdls.put(entry.getKey(), entry.getValue().asText());
                });
            }
        }

        if (sdls.isEmpty()) {
            throw new IOException("No subgraphs found in: " + schemaYamlPath);
        }

        return sdls;
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
                            throw new RuntimeException("Upload failed with status " + r.statusCode() + ": " + body);
                        });
                }
                return r.bodyToMono(Map.class);
            })
            .block();

        assertNotNull(uploadResponse);
        assertEquals(true, uploadResponse.get("success"), "Schema upload should succeed");
    }

    private void runExecutionTest(DynamicSubgraphServers servers, SchemaContext schemaContext, ExecutionTest test) {
        // YAML fixtures may carry `skip: true` to document expected
        // future behavior for spec rules not yet implemented; the
        // runner skips them rather than failing.
        if (test.shouldSkip()) {
            log.info("  SKIPPED (forward-looking fixture, not yet implemented)");
            Assumptions.assumeFalse(true, "Test marked as skip: " + test.name());
            return;
        }

        // 1. Re-upload schema if test has custom timeout
        if (test.timeoutMs() != null) {
            byte[] zip = SchemaZipBuilder.createZip(
                schemaContext.subgraphSdls,
                schemaContext.subgraphUrls,
                test.timeoutMs()
            );
            uploadSchemaToGateway(zip);
        }

        // 2. Configure WireMock stubs
        SubgraphStubBuilder.configureStubs(servers, test);

        // 3. Execute query
        @SuppressWarnings("unchecked")
        Map<String, Object> response = gatewayClient.post()
            .uri("/graphql")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of(
                "query", test.query(),
                "variables", test.variables()
            ))
            .exchangeToMono(r -> {
                if (r.statusCode().isError()) {
                    return r.bodyToMono(String.class)
                        .map(body -> {
                            throw new RuntimeException("GraphQL request failed with status " + r.statusCode() + ": " + body);
                        });
                }
                return r.bodyToMono(Map.class);
            })
            .block();

        // 4. Assert response matches expected
        assertNotNull(response, "Response should not be null");
        assertResponseMatches(test.expectedResponse(), response, test.name());

        // 5. Restore default timeout if we changed it
        if (test.timeoutMs() != null) {
            byte[] zip = SchemaZipBuilder.createZip(
                schemaContext.subgraphSdls,
                schemaContext.subgraphUrls
            );
            uploadSchemaToGateway(zip);
        }
    }

    /**
     * Asserts that the response matches expected, using lenient comparison for errors
     * (allowing extra fields like 'classification').
     */
    @SuppressWarnings("unchecked")
    private void assertResponseMatches(Map<String, Object> expected, Map<String, Object> actual, String testName) {
        // Compare data
        if (!java.util.Objects.equals(expected.get("data"), actual.get("data"))) {
            log.error("=== RESPONSE MISMATCH for test: {} ===", testName);
            log.error("Expected data: {}", expected.get("data"));
            log.error("Actual data:   {}", actual.get("data"));
            log.error("Full expected: {}", expected);
            log.error("Full actual:   {}", actual);
            log.error("=== END MISMATCH ===");
        }
        assertEquals(expected.get("data"), actual.get("data"),
            "Response data should match expected for test: " + testName
            + "\nExpected: " + expected.get("data")
            + "\nActual:   " + actual.get("data"));

        // Compare errors with lenient matching
        List<Map<String, Object>> expectedErrors = (List<Map<String, Object>>) expected.get("errors");
        List<Map<String, Object>> actualErrorsList = (List<Map<String, Object>>) actual.get("errors");

        if (expectedErrors == null || expectedErrors.isEmpty()) {
            assertTrue(actualErrorsList == null || actualErrorsList.isEmpty(),
                "Expected no errors but got: " + actualErrorsList + " for test: " + testName);
        } else {
            assertNotNull(actualErrorsList, "Expected errors but got none for test: " + testName);
            assertEquals(expectedErrors.size(), actualErrorsList.size(),
                "Error count mismatch for test: " + testName);

            for (int i = 0; i < expectedErrors.size(); i++) {
                Map<String, Object> expectedError = expectedErrors.get(i);
                Map<String, Object> actualError = actualErrorsList.get(i);

                // Check message
                assertEquals(expectedError.get("message"), actualError.get("message"),
                    "Error message mismatch at index " + i + " for test: " + testName);

                // Check extensions (lenient - actual may have extra fields)
                Map<String, Object> expectedExtensions = (Map<String, Object>) expectedError.get("extensions");
                Map<String, Object> actualExtensions = (Map<String, Object>) actualError.get("extensions");

                if (expectedExtensions != null) {
                    assertNotNull(actualExtensions, "Expected extensions but got none for test: " + testName);
                    for (Map.Entry<String, Object> entry : expectedExtensions.entrySet()) {
                        assertEquals(entry.getValue(), actualExtensions.get(entry.getKey()),
                            "Extension '" + entry.getKey() + "' mismatch for test: " + testName);
                    }
                }
            }
        }
    }
}
