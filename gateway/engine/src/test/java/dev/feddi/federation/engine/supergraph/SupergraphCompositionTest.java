package dev.feddi.federation.engine.supergraph;

import dev.feddi.federation.engine.compose.Composer;
import dev.feddi.federation.engine.compose.CompositionResult;
import dev.feddi.federation.engine.compose.validation.Diagnostic;
import graphql.language.Document;
import graphql.parser.Parser;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaPrinter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test runner for supergraph composition tests.
 * 
 * Loads YAML test cases from resources/composition/ and verifies that:
 * - Positive tests: the composed supergraph matches the expected supergraph
 * - Negative tests: composition fails with the expected error code
 */
class SupergraphCompositionTest {

    private static final Logger log = LoggerFactory.getLogger(SupergraphCompositionTest.class);

    private static SupergraphTestLoader loader;
    private static Composer composer;
    private static SchemaPrinter schemaPrinter;
    private static Parser parser;
    
    @BeforeAll
    static void setUp() {
        loader = new SupergraphTestLoader();
        composer = new Composer();
        // Configure SchemaPrinter to produce comparable output
        schemaPrinter = new SchemaPrinter(SchemaPrinter.Options.defaultOptions()
            .includeDirectives(false)
            .includeDirectiveDefinitions(false)
            .includeScalarTypes(false)
            .includeSchemaDefinition(false));
        parser = new Parser();
    }
    
    @TestFactory
    @DisplayName("Supergraph Composition Tests")
    Stream<DynamicTest> supergraphCompositionTests() throws IOException, URISyntaxException {
        URL compositionUrl = getClass().getClassLoader().getResource("composition");
        if (compositionUrl == null) {
            log.warn("No composition test resources found");
            return Stream.empty();
        }
        
        Path compositionDir = Paths.get(compositionUrl.toURI());
        List<SupergraphTestCase> testCases = loader.loadAll(compositionDir);
        
        return testCases.stream()
            .map(this::createTest);
    }
    
    private DynamicTest createTest(SupergraphTestCase testCase) {
        return DynamicTest.dynamicTest(
            testCase.name(),
            () -> runTest(testCase)
        );
    }
    
    private void runTest(SupergraphTestCase testCase) {
        log.info("Running: {}", testCase.name());
        if (testCase.description() != null) {
            log.debug("  {}", testCase.description());
        }
        
        // Build SubgraphInputs from the test case
        List<Composer.SubgraphInput> inputs = new ArrayList<>();
        testCase.subgraphs().forEach((name, sdl) -> {
            inputs.add(Composer.SubgraphInput.of(name, sdl));
        });
        
        // Compose the supergraph
        CompositionResult result = composer.compose(inputs);
        
        if (testCase.expectsSuccess()) {
            runPositiveTest(testCase, result);
        } else {
            runNegativeTest(testCase, result);
        }
    }
    
    /**
     * Runs a positive test - composition should succeed and match expected supergraph.
     */
    private void runPositiveTest(SupergraphTestCase testCase, CompositionResult result) {
        if (!result.isSuccess()) {
            log.warn("  Composition failed (unexpectedly):");
            result.validationResult().errors().forEach(error ->
                log.warn("    - {}: {}", error.code(), error.message()));
        }
        
        assertThat(result.isSuccess())
            .as("Composition should succeed for: %s", testCase.name())
            .isTrue();
        
        GraphQLSchema generatedSupergraph = result.supergraph();
        assertThat(generatedSupergraph)
            .as("Supergraph should not be null")
            .isNotNull();
        
        // Print the generated supergraph
        String actualSdl = schemaPrinter.print(generatedSupergraph);
        
        // Parse and print the expected supergraph for normalized comparison
        Document expectedDocument = parser.parseDocument(testCase.supergraph());
        String expectedSdl = schemaPrinter.print(expectedDocument);
        
        log.debug("Expected supergraph:\n{}", expectedSdl);
        log.debug("Actual supergraph:\n{}", actualSdl);

        // Compare the normalized SDL strings (just whitespace normalization)
        assertThat(normalizeWhitespace(actualSdl))
            .as("Supergraph should match expected for: %s", testCase.name())
            .isEqualTo(normalizeWhitespace(expectedSdl));

        log.debug("  PASSED");
    }
    
    /**
     * Runs a negative test - composition should fail with the expected error code.
     */
    private void runNegativeTest(SupergraphTestCase testCase, CompositionResult result) {
        String expectedError = testCase.error();
        
        assertThat(result.isSuccess())
            .as("Composition should fail for: %s (expected error: %s)", testCase.name(), expectedError)
            .isFalse();
        
        List<String> errorCodes = result.validationResult().errors().stream()
            .map(Diagnostic::code)
            .toList();
        
        log.debug("  Expected error: {}", expectedError);
        log.debug("  Actual errors: {}", errorCodes);

        assertThat(errorCodes)
            .as("Should contain expected error code '%s' for: %s", expectedError, testCase.name())
            .contains(expectedError);

        log.debug("  PASSED (validation error as expected)");
    }
    
    /**
     * Normalizes whitespace in SDL for consistent comparison.
     */
    private String normalizeWhitespace(String sdl) {
        return sdl.replaceAll("\\s+", " ").trim();
    }
}
