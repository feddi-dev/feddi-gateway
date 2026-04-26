package dev.feddi.federation.engine.planner;

import dev.feddi.federation.engine.testcase.QueryTest;
import dev.feddi.federation.engine.testcase.SchemaDefinition;
import dev.feddi.federation.engine.testcase.TestCaseLoader;
import graphql.language.AstPrinter;
import graphql.language.Document;
import graphql.parser.Parser;
import graphql.schema.GraphQLSchema;
import graphql.validation.ValidationError;
import graphql.validation.Validator;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test runner for YAML-based query planner test cases.
 * 
 * Expected folder structure:
 * schemas/
 *   schema_name/
 *     schema.yaml
 *     planning/
 *       test1.yaml
 *       test2.yaml
 */
class OperationPlannerTest {

    private static final Logger log = LoggerFactory.getLogger(OperationPlannerTest.class);

    private static TestCaseLoader loader;
    
    @BeforeAll
    static void setUp() {
        loader = new TestCaseLoader();
    }
    
    @TestFactory
    @DisplayName("Schema Test Suites")
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
            });
    }
    
    private DynamicContainer createSchemaTestContainer(Path schemaDir) throws IOException {
        String schemaName = schemaDir.getFileName().toString();
        
        // Load schema
        Path schemaFile = schemaDir.resolve("schema.yaml");
        if (!Files.exists(schemaFile)) {
            throw new IOException("schema.yaml not found in " + schemaDir);
        }
        SchemaDefinition schema = loader.loadSchema(schemaFile);
        
        // Load planning tests
        Path planningDir = schemaDir.resolve("planning");
        List<DynamicTest> tests = new ArrayList<>();
        
        if (Files.exists(planningDir) && Files.isDirectory(planningDir)) {
            Files.list(planningDir)
                .filter(path -> path.toString().endsWith(".yaml"))
                .sorted()
                .forEach(queryFile -> {
                    try {
                        QueryTest queryTest = loader.loadQueryTest(queryFile, schema.supergraphSchema());
                        String testName = queryFile.getFileName().toString().replace(".yaml", "");
                        tests.add(DynamicTest.dynamicTest(
                            testName + ": " + queryTest.name(),
                            () -> runQueryTest(schema, queryTest)
                        ));
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to load query: " + queryFile, e);
                    }
                });
        }
        
        return DynamicContainer.dynamicContainer(
            schemaName + " (" + schema.name() + ")",
            tests
        );
    }
    
    private void runQueryTest(SchemaDefinition schema, QueryTest queryTest) {
        log.info("Running test: {}", queryTest.name());
        if (queryTest.description() != null) {
            log.debug("  Description: {}", queryTest.description());
        }

        OperationPlanner planner = new OperationPlanner(schema.graph());
        ExecutionPlan actualPlan = planner.plan(queryTest.operation());

        if (log.isDebugEnabled()) {
            log.debug("  Actual plan:");
            for (ExecutionStep step : actualPlan.steps()) {
                log.debug("    {}", step);
            }
        }

        assertThat(actualPlan.steps())
            .as("Plan should have at least one step")
            .isNotEmpty();

        // Validate each step's operation against its subgraph schema
        validateOperationsAgainstSubgraphs(actualPlan, schema);

        if (queryTest.hasExpectedPlan()) {
            verifyPlan(actualPlan, queryTest.expectedPlan());
        }

        log.debug("  PASSED");
    }

    /**
     * Validates that each step's operation is a valid GraphQL query against its subgraph schema.
     */
    private void validateOperationsAgainstSubgraphs(ExecutionPlan plan, SchemaDefinition schema) {
        Validator validator = new Validator();
        Parser parser = new Parser();

        for (ExecutionStep step : plan.steps()) {
            // Skip validation for the virtual $introspection subgraph
            // Introspection operations are always valid by definition
            if ("$introspection".equals(step.subgraph())) {
                continue;
            }

            GraphQLSchema subgraphSchema = schema.getSubgraphSchema(step.subgraph());
            assertThat(subgraphSchema)
                .as("Subgraph schema for '%s' should exist", step.subgraph())
                .isNotNull();

            String operationText = step.toGraphQL();
            Document document = parser.parseDocument(operationText);

            List<ValidationError> errors = validator.validateDocument(subgraphSchema, document, Locale.ENGLISH);

            if (!errors.isEmpty()) {
                String errorMessages = errors.stream()
                    .map(ValidationError::getMessage)
                    .collect(Collectors.joining("\n  - "));
                throw new AssertionError(String.format(
                    "Operation for step %d (%s) is not valid against subgraph schema:\n" +
                    "  Operation: %s\n" +
                    "  Errors:\n  - %s",
                    step.id(), step.subgraph(), operationText, errorMessages
                ));
            }
        }
    }
    
    private void verifyPlan(ExecutionPlan actual, ExecutionPlan expected) {
        assertThat(actual.stepCount())
            .as("Number of steps")
            .isEqualTo(expected.stepCount());

        for (int i = 0; i < expected.steps().size(); i++) {
            ExecutionStep expectedStep = expected.steps().get(i);
            // Find by ID first (for cases with multiple steps in same subgraph), fall back to subgraph
            ExecutionStep actualStep = findStepById(actual, expectedStep.id());
            if (actualStep == null) {
                actualStep = findStepBySubgraph(actual, expectedStep.subgraph());
            }

            assertThat(actualStep)
                .as("Step %d for subgraph '%s'", expectedStep.id(), expectedStep.subgraph())
                .isNotNull();

            assertThat(actualStep.flattenedFields())
                .as("Fields for step %d (%s)", i + 1, expectedStep.subgraph())
                .containsAll(expectedStep.flattenedFields());

            assertThat(actualStep.dependsOn().size())
                .as("Dependency count for step %d", i + 1)
                .isEqualTo(expectedStep.dependsOn().size());

            assertThat(actualStep.requirements())
                .as("Requirements for step %d (%s)", i + 1, expectedStep.subgraph())
                .isEqualTo(expectedStep.requirements());

            assertThat(actualStep.repeatedExecution())
                .as("repeatedExecution for step %d (%s)", i + 1, expectedStep.subgraph())
                .isEqualTo(expectedStep.repeatedExecution());

            // Verify parallelWith if specified in expected plan
            if (!expectedStep.parallelWith().isEmpty()) {
                assertThat(actualStep.parallelWith())
                    .as("parallelWith for step %d (%s)", i + 1, expectedStep.subgraph())
                    .containsExactlyInAnyOrderElementsOf(expectedStep.parallelWith());
            }

            // Verify operation matches (including variable definitions)
            assertThat(normalizeGraphQL(actualStep.toGraphQL()))
                .as("Operation for step %d (%s)", i + 1, expectedStep.subgraph())
                .isEqualTo(normalizeGraphQL(expectedStep.toGraphQL()));
        }
    }

    /**
     * Normalizes a GraphQL string for comparison by parsing and re-printing in compact form.
     */
    private String normalizeGraphQL(String graphql) {
        Document doc = Parser.parse(graphql);
        return AstPrinter.printAstCompact(doc);
    }
    
    private ExecutionStep findStepBySubgraph(ExecutionPlan plan, String subgraph) {
        return plan.steps().stream()
            .filter(s -> s.subgraph().equals(subgraph))
            .findFirst()
            .orElse(null);
    }

    private ExecutionStep findStepById(ExecutionPlan plan, int id) {
        return plan.steps().stream()
            .filter(s -> s.id() == id)
            .findFirst()
            .orElse(null);
    }
}
