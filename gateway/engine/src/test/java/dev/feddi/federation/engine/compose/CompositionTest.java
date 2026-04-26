package dev.feddi.federation.engine.compose;

import dev.feddi.federation.engine.testcase.CompositionTestCaseLoader;
import dev.feddi.federation.engine.testcase.CompositionTestCaseLoader.CompositionExpectation;
import dev.feddi.federation.engine.testcase.CompositionTestCaseLoader.ExpectedDiagnostic;
import dev.feddi.federation.engine.testcase.CompositionTestCaseLoader.SourceSchema;
import dev.feddi.federation.engine.testcase.CompositionTestCaseLoader.TestCase;
import dev.feddi.federation.engine.compose.validation.Diagnostic;
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
 * Runs composition tests from the tests/composition directory.
 */
class CompositionTest {

    private static final Path TESTS_DIR = Paths.get("..", "tests", "composition");

    private final CompositionTestCaseLoader loader = new CompositionTestCaseLoader();
    private final Composer composer = new Composer();
    private final SubgraphParser parser = new SubgraphParser();

    @TestFactory
    Stream<DynamicTest> compositionTests() throws IOException {
        if (!TESTS_DIR.toFile().exists()) {
            return Stream.empty();
        }

        List<TestCase> testCases = loader.loadAll(TESTS_DIR);

        return testCases.stream()
            .filter(TestCase::isCompositionTest)
            .map(this::createTest);
    }

    private DynamicTest createTest(TestCase testCase) {
        String displayName = String.format("[%s] %s",
            testCase.category(), testCase.name());

        return DynamicTest.dynamicTest(displayName, () -> runTest(testCase));
    }

    private void runTest(TestCase testCase) {
        // Parse subgraphs
        List<Subgraph> subgraphs = new ArrayList<>();
        for (SourceSchema schema : testCase.sourceSchemas()) {
            Subgraph subgraph = parser.parse(schema.name(), schema.url(), schema.sdl());
            subgraphs.add(subgraph);
        }

        // Run composition
        CompositionResult result = composer.compose(subgraphs, true);

        // Verify expectations
        CompositionExpectation expectation = testCase.composition();
        if (expectation == null) {
            // No explicit expectation, just verify it doesn't crash
            return;
        }

        if (expectation.expectsSuccess()) {
            assertThat(result.isSuccess())
                .as("Composition should succeed for test: %s", testCase.name())
                .isTrue();

            assertThat(result.graph())
                .as("Graph should be present for successful composition")
                .isNotNull();
        } else if (expectation.expectsError()) {
            assertThat(result.isFailure())
                .as("Composition should fail for test: %s", testCase.name())
                .isTrue();

            // Verify expected diagnostics
            verifyDiagnostics(expectation.diagnostics(), result.validationResult().diagnostics());
        }
    }

    private void verifyDiagnostics(List<ExpectedDiagnostic> expected, List<Diagnostic> actual) {
        for (ExpectedDiagnostic expectedDiag : expected) {
            boolean found = actual.stream().anyMatch(actualDiag ->
                expectedDiag.code().equals(actualDiag.code())
            );

            assertThat(found)
                .as("Expected diagnostic with code: %s", expectedDiag.code())
                .isTrue();
        }
    }
}
