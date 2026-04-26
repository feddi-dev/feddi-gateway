package dev.feddi.federation.engine.compose.validation;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.SubgraphParser;
import dev.feddi.federation.engine.testcase.CompositionTestCaseLoader;
import dev.feddi.federation.engine.testcase.CompositionTestCaseLoader.ExpectedDiagnostic;
import dev.feddi.federation.engine.testcase.CompositionTestCaseLoader.SourceSchema;
import dev.feddi.federation.engine.testcase.CompositionTestCaseLoader.TestCase;
import dev.feddi.federation.engine.testcase.CompositionTestCaseLoader.ValidationExpectation;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs validation tests from the tests/validation directory.
 */
class ValidationTest {

    private static final Path TESTS_DIR = Paths.get("..", "tests", "validation");

    private final CompositionTestCaseLoader loader = new CompositionTestCaseLoader();
    private final SubgraphParser parser = new SubgraphParser();
    private final SchemaValidator validator = SchemaValidator.withDefaultRules();

    @TestFactory
    Stream<DynamicTest> validationTests() throws IOException {
        if (!TESTS_DIR.toFile().exists()) {
            return Stream.empty();
        }

        List<TestCase> testCases = loader.loadAll(TESTS_DIR);

        return testCases.stream()
            .filter(TestCase::isValidationTest)
            .map(this::createTest);
    }

    private DynamicTest createTest(TestCase testCase) {
        String displayName = String.format("[%s] %s",
            testCase.validation() != null ? testCase.validation().phase() : "validation",
            testCase.name());

        return DynamicTest.dynamicTest(displayName, () -> runTest(testCase));
    }

    private void runTest(TestCase testCase) {
        // Parse subgraphs
        List<Subgraph> subgraphs = new ArrayList<>();
        for (SourceSchema schema : testCase.sourceSchemas()) {
            Subgraph subgraph = parser.parse(schema.name(), schema.url(), schema.sdl());
            subgraphs.add(subgraph);
        }

        ValidationExpectation expectation = testCase.validation();
        if (expectation == null) {
            return;
        }

        // Determine which phase to validate
        ValidationPhase phase = parsePhase(expectation.phase());

        // Run validation
        ValidationResult result = validator.validate(subgraphs, phase);

        // Verify expectations
        if (expectation.expectsValid()) {
            assertThat(result.isValid())
                .as("Validation should pass for test: %s\nErrors: %s",
                    testCase.name(), result.errors())
                .isTrue();
        } else if (expectation.expectsInvalid()) {
            assertThat(result.hasErrors())
                .as("Validation should fail for test: %s", testCase.name())
                .isTrue();

            // Verify expected diagnostics
            verifyDiagnostics(expectation.diagnostics(), result.diagnostics());
        }
    }

    private ValidationPhase parsePhase(String phase) {
        return switch (phase) {
            case "source_schema" -> ValidationPhase.SOURCE_SCHEMA;
            case "pre_merge" -> ValidationPhase.PRE_MERGE;
            case "post_merge" -> ValidationPhase.POST_MERGE;
            default -> ValidationPhase.SOURCE_SCHEMA;
        };
    }

    private void verifyDiagnostics(List<ExpectedDiagnostic> expected, List<Diagnostic> actual) {
        for (ExpectedDiagnostic expectedDiag : expected) {
            boolean found = actual.stream().anyMatch(actualDiag ->
                expectedDiag.code().equals(actualDiag.code())
            );

            assertThat(found)
                .as("Expected diagnostic with code: %s\nActual diagnostics: %s",
                    expectedDiag.code(), actual)
                .isTrue();
        }
    }
}
