package dev.feddi.federation.engine.executor;

import dev.feddi.federation.engine.ResponseFieldValidator;
import dev.feddi.federation.engine.planner.ExecutionPlan;
import dev.feddi.federation.engine.query.Operation;
import dev.feddi.federation.engine.query.OperationNormalizer;
import dev.feddi.federation.engine.planner.OperationPlanner;
import graphql.schema.GraphQLSchema;
import dev.feddi.federation.engine.testcase.ExecutionTest;
import dev.feddi.federation.engine.testcase.SchemaDefinition;
import dev.feddi.federation.engine.testcase.SubgraphCall;
import dev.feddi.federation.engine.testcase.TestCaseLoader;
import graphql.ExecutionResult;
import graphql.GraphQLError;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test runner for YAML-based execution tests.
 * 
 * Expected folder structure:
 * schemas/
 *   schema_name/
 *     schema.yaml
 *     executions/
 *       execution1.yaml
 *       execution2.yaml
 */
class ExecutorTest {

    private static final Logger log = LoggerFactory.getLogger(ExecutorTest.class);
    private static TestCaseLoader loader;

    @BeforeAll
    static void setUp() {
        loader = new TestCaseLoader();
    }
    
    @TestFactory
    @DisplayName("Execution Test Suites")
    Stream<DynamicContainer> testAllSchemas() throws IOException, URISyntaxException {
        URL schemasUrl = getClass().getClassLoader().getResource("schemas");
        if (schemasUrl == null) {
            return Stream.empty();
        }
        
        Path schemasDir = Paths.get(schemasUrl.toURI());
        
        return Files.list(schemasDir)
            .filter(Files::isDirectory)
            .sorted()
            .map(schemaDir -> {
                try {
                    return createSchemaTestContainer(schemaDir);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to load schema: " + schemaDir, e);
                }
            })
            .filter(container -> container != null);
    }
    
    private DynamicContainer createSchemaTestContainer(Path schemaDir) throws IOException {
        String schemaName = schemaDir.getFileName().toString();
        
        // Check if executions folder exists
        Path executionsDir = schemaDir.resolve("executions");
        if (!Files.exists(executionsDir) || !Files.isDirectory(executionsDir)) {
            return null; // No execution tests for this schema
        }
        
        // Load schema
        Path schemaFile = schemaDir.resolve("schema.yaml");
        if (!Files.exists(schemaFile)) {
            throw new IOException("schema.yaml not found in " + schemaDir);
        }
        SchemaDefinition schema = loader.loadSchema(schemaFile);
        
        // Load execution tests
        List<DynamicTest> tests = new ArrayList<>();
        
        Files.list(executionsDir)
            .filter(path -> path.toString().endsWith(".yaml"))
            .sorted()
            .forEach(executionFile -> {
                try {
                    ExecutionTest executionTest = loader.loadExecutionTest(executionFile);
                    String testName = executionFile.getFileName().toString().replace(".yaml", "");
                    tests.add(DynamicTest.dynamicTest(
                        testName + ": " + executionTest.name(),
                        () -> runExecutionTest(schema, executionTest)
                    ));
                } catch (IOException e) {
                    throw new RuntimeException("Failed to load execution test: " + executionFile, e);
                }
            });
        
        if (tests.isEmpty()) {
            return null;
        }
        
        return DynamicContainer.dynamicContainer(
            schemaName + " (" + schema.name() + ") - Execution",
            tests
        );
    }
    
    private void runExecutionTest(SchemaDefinition schema, ExecutionTest executionTest) {
        log.info("Running execution test: {}", executionTest.name());
        if (executionTest.description() != null) {
            log.debug("  Description: {}", executionTest.description());
        }
        if (executionTest.finishOrder() != null) {
            log.debug("  Finish order: {}", executionTest.finishOrder());
        }
        if (executionTest.timeoutMs() != null) {
            log.debug("  Timeout: {} ms", executionTest.timeoutMs());
        }

        // YAML fixtures may carry `skip: true` to document expected
        // future behavior for spec rules not yet implemented; the
        // runner skips them rather than failing.
        if (executionTest.shouldSkip()) {
            log.info("  SKIPPED (forward-looking fixture, not yet implemented)");
            Assumptions.assumeFalse(true, "Test marked as skip: " + executionTest.name());
            return;
        }

        // Parse the query and plan it
        OperationNormalizer normalizer = OperationNormalizer.builder(schema.supergraphSchema())
            .inlineFragments(true)
            .deduplicateFields(true)
            .sortSelections(false)  // Don't sort to preserve query field order
            .processSkipInclude(true)  // Evaluate literal @skip/@include at planning time
            .build();
        Operation query = Operation.parse(executionTest.query(), normalizer);
        OperationPlanner planner = new OperationPlanner(schema.graph());
        ExecutionPlan plan = planner.plan(query);

        // Create finish order controller if specified
        FinishOrderController finishOrderController = executionTest.finishOrder() != null
            ? new FinishOrderController(executionTest.finishOrder())
            : null;

        // Validate subgraph mock responses BEFORE execution (executor mutates maps during merge)
        validateSubgraphMockResponses(executionTest.subgraphCalls());

        // Create mock clients from expected subgraph calls
        Map<String, SubgraphClient> clients = createMockClients(
            schema,
            executionTest.subgraphCalls(),
            finishOrderController,
            executionTest.timeoutMs()
        );

        // Execute the plan
        Executor executor = new Executor(clients);
        ExecutionResult result = executor.execute(plan, executionTest.variables()).block();

        // Verify the data result
        @SuppressWarnings("unchecked")
        Map<String, Object> actualData = (Map<String, Object>) result.getData();
        @SuppressWarnings("unchecked")
        Map<String, Object> expectedData = (Map<String, Object>) executionTest.expectedResponse().get("data");

        assertThat(actualData)
            .as("Execution result data should match expected")
            .isEqualTo(expectedData);

        // Validate supergraph response (informational only until executor is fixed)
        validateSupergraphResponse(query, actualData);

        // Verify errors if expected
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> expectedErrors = (List<Map<String, Object>>) executionTest.expectedResponse().get("errors");
        List<GraphQLError> actualErrors = result.getErrors();

        if (expectedErrors != null && !expectedErrors.isEmpty()) {
            log.debug("  Expected errors: {}", expectedErrors);
            log.debug("  Actual errors: {}", actualErrors);

            assertThat(actualErrors)
                .as("Should have the expected number of errors")
                .hasSize(expectedErrors.size());

            for (int i = 0; i < expectedErrors.size(); i++) {
                Map<String, Object> expectedError = expectedErrors.get(i);
                GraphQLError actualError = actualErrors.get(i);

                // Verify error message
                String expectedMessage = (String) expectedError.get("message");
                if (expectedMessage != null) {
                    assertThat(actualError.getMessage())
                        .as("Error message should match")
                        .isEqualTo(expectedMessage);
                }

                // Verify error extensions
                @SuppressWarnings("unchecked")
                Map<String, Object> expectedExtensions = (Map<String, Object>) expectedError.get("extensions");
                if (expectedExtensions != null) {
                    assertThat(actualError.getExtensions())
                        .as("Error extensions should match")
                        .containsAllEntriesOf(expectedExtensions);
                }
            }
        } else {
            // No errors expected - verify none present
            if (!actualErrors.isEmpty()) {
                log.warn("  Unexpected errors: {}", actualErrors);
            }
            assertThat(actualErrors)
                .as("Should have no errors")
                .isEmpty();
        }

        log.debug("  PASSED");
    }

    /**
     * Creates mock clients from the expected subgraph calls.
     * Groups calls by subgraph name and applies optional finish order control.
     * If timeoutMs is specified, wraps clients with timeout behavior.
     *
     * Uses ExecutingMockSubgraphClient which executes operations through GraphQL Java
     * to validate that planner-generated operations are syntactically and semantically valid.
     */
    private Map<String, SubgraphClient> createMockClients(SchemaDefinition schema,
                                                          List<SubgraphCall> calls,
                                                          FinishOrderController finishOrderController,
                                                          Long timeoutMs) {
        // Group calls by subgraph
        Map<String, List<SubgraphCall>> callsBySubgraph = new LinkedHashMap<>();
        for (SubgraphCall call : calls) {
            callsBySubgraph
                .computeIfAbsent(call.subgraph(), k -> new ArrayList<>())
                .add(call);
        }

        // Create mock client for each subgraph
        Map<String, SubgraphClient> clients = new LinkedHashMap<>();
        for (Map.Entry<String, List<SubgraphCall>> entry : callsBySubgraph.entrySet()) {
            String subgraphName = entry.getKey();
            graphql.schema.GraphQLSchema subgraphSchema = schema.getSubgraphSchema(subgraphName);

            SubgraphClient mockClient = new ExecutingMockSubgraphClient(
                subgraphName,
                subgraphSchema,
                entry.getValue(),
                finishOrderController
            );

            // Wrap with timeout if specified
            if (timeoutMs != null) {
                mockClient = new TimeoutTestSubgraphClient(
                    mockClient,
                    subgraphName,
                    Duration.ofMillis(timeoutMs)
                );
            }

            clients.put(subgraphName, mockClient);
        }

        return clients;
    }

    /**
     * Validates subgraph mock responses BEFORE execution.
     * Must be called before execution because the executor mutates response maps during merge.
     */
    private void validateSubgraphMockResponses(List<SubgraphCall> subgraphCalls) {
        for (SubgraphCall call : subgraphCalls) {
            @SuppressWarnings("unchecked")
            Map<String, Object> responseData = (Map<String, Object>) call.response().get("data");
            if (responseData != null) {
                List<String> errors = ResponseFieldValidator.validate(call.operation(), responseData);
                if (!errors.isEmpty()) {
                    log.warn("  Mock response validation errors (subgraph {}):", call.subgraph());
                    log.warn("    Operation: {}", call.operation());
                    errors.forEach(e -> log.warn("    - {}", e));
                }
                assertThat(errors)
                    .as("Subgraph '%s' mock response should only contain requested fields. Operation: %s",
                        call.subgraph(), call.operation())
                    .isEmpty();
            }
        }
    }

    /**
     * Validates supergraph response against original query.
     * Ensures that the response only contains fields that were requested by the client.
     */
    private void validateSupergraphResponse(Operation query, Map<String, Object> actualData) {
        List<String> errors = ResponseFieldValidator.validate(query, actualData);
        if (!errors.isEmpty()) {
            log.warn("  Supergraph response validation errors:");
            errors.forEach(e -> log.warn("    - {}", e));
        }
        assertThat(errors)
            .as("Supergraph response should only contain requested fields")
            .isEmpty();
    }
}
