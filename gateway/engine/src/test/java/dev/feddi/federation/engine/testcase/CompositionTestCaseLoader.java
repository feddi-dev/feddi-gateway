package dev.feddi.federation.engine.testcase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads composition test cases from YAML files in the tests/ directory.
 */
public final class CompositionTestCaseLoader {

    private final ObjectMapper yamlMapper;

    public CompositionTestCaseLoader() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
    }

    /**
     * Loads a test case from a YAML file.
     */
    public TestCase load(Path yamlFile) throws IOException {
        JsonNode root = yamlMapper.readTree(yamlFile.toFile());
        return parseTestCase(root, yamlFile.toString());
    }

    /**
     * Loads a test case from an input stream.
     */
    public TestCase load(InputStream inputStream, String sourceName) throws IOException {
        JsonNode root = yamlMapper.readTree(inputStream);
        return parseTestCase(root, sourceName);
    }

    /**
     * Loads all test cases from a directory recursively.
     */
    public List<TestCase> loadAll(Path directory) throws IOException {
        List<TestCase> testCases = new ArrayList<>();

        try (var stream = Files.walk(directory)) {
            stream.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                .filter(Files::isRegularFile)
                .forEach(path -> {
                    try {
                        // Handle multi-document YAML files
                        List<TestCase> cases = loadMultiDocument(path);
                        testCases.addAll(cases);
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to load: " + path, e);
                    }
                });
        }

        return testCases;
    }

    /**
     * Loads multiple test cases from a multi-document YAML file.
     */
    public List<TestCase> loadMultiDocument(Path yamlFile) throws IOException {
        List<TestCase> testCases = new ArrayList<>();
        String content = Files.readString(yamlFile);

        // Split by YAML document separator
        String[] documents = content.split("(?m)^---\\s*$");

        for (String doc : documents) {
            if (doc.trim().isEmpty()) continue;

            JsonNode root = yamlMapper.readTree(doc);
            if (root != null && root.has("name")) {
                testCases.add(parseTestCase(root, yamlFile.toString()));
            }
        }

        return testCases;
    }

    private TestCase parseTestCase(JsonNode root, String sourcePath) {
        String name = root.path("name").asText();
        String description = root.path("description").asText(null);
        String category = root.path("category").asText();

        List<String> tags = new ArrayList<>();
        JsonNode tagsNode = root.path("tags");
        if (tagsNode.isArray()) {
            for (JsonNode tag : tagsNode) {
                tags.add(tag.asText());
            }
        }

        String specReference = root.path("spec_reference").asText(null);
        String source = root.path("source").asText(null);

        List<SourceSchema> sourceSchemas = parseSourceSchemas(root.path("source_schemas"));
        CompositionExpectation composition = parseComposition(root.path("composition"));
        ValidationExpectation validation = parseValidation(root.path("validation"));

        return new TestCase(
            name, description, category, tags, specReference, source,
            sourceSchemas, composition, validation, sourcePath
        );
    }

    private List<SourceSchema> parseSourceSchemas(JsonNode schemasNode) {
        List<SourceSchema> schemas = new ArrayList<>();

        if (schemasNode.isArray()) {
            for (JsonNode schemaNode : schemasNode) {
                String name = schemaNode.path("name").asText();
                String url = schemaNode.path("url").asText(null);
                String sdl = schemaNode.path("sdl").asText();
                schemas.add(new SourceSchema(name, url, sdl));
            }
        }

        return schemas;
    }

    private CompositionExpectation parseComposition(JsonNode compositionNode) {
        if (compositionNode.isMissingNode()) {
            return null;
        }

        String expect = compositionNode.path("expect").asText();
        List<ExpectedDiagnostic> diagnostics = parseDiagnostics(compositionNode.path("diagnostics"));

        return new CompositionExpectation(expect, diagnostics);
    }

    private ValidationExpectation parseValidation(JsonNode validationNode) {
        if (validationNode.isMissingNode()) {
            return null;
        }

        String phase = validationNode.path("phase").asText();
        String rule = validationNode.path("rule").asText(null);
        String expect = validationNode.path("expect").asText();
        List<ExpectedDiagnostic> diagnostics = parseDiagnostics(validationNode.path("diagnostics"));

        return new ValidationExpectation(phase, rule, expect, diagnostics);
    }

    private List<ExpectedDiagnostic> parseDiagnostics(JsonNode diagnosticsNode) {
        List<ExpectedDiagnostic> diagnostics = new ArrayList<>();

        if (diagnosticsNode.isArray()) {
            for (JsonNode diagNode : diagnosticsNode) {
                String code = diagNode.path("code").asText();
                String message = diagNode.path("message").asText(null);
                String severity = diagNode.path("severity").asText();
                String coordinate = diagNode.path("coordinate").asText(null);
                String schema = diagNode.path("schema").asText(null);
                String member = diagNode.path("member").asText(null);

                diagnostics.add(new ExpectedDiagnostic(code, message, severity, coordinate, schema, member));
            }
        }

        return diagnostics;
    }

    /**
     * Represents a test case.
     */
    public record TestCase(
        String name,
        String description,
        String category,
        List<String> tags,
        String specReference,
        String source,
        List<SourceSchema> sourceSchemas,
        CompositionExpectation composition,
        ValidationExpectation validation,
        String sourcePath
    ) {
        public boolean isCompositionTest() {
            return "composition".equals(category) || composition != null;
        }

        public boolean isValidationTest() {
            return "validation".equals(category) || validation != null;
        }
    }

    /**
     * Represents a source schema in a test case.
     */
    public record SourceSchema(String name, String url, String sdl) {}

    /**
     * Expected composition result.
     */
    public record CompositionExpectation(String expect, List<ExpectedDiagnostic> diagnostics) {
        public boolean expectsSuccess() {
            return "success".equals(expect);
        }

        public boolean expectsError() {
            return "error".equals(expect);
        }
    }

    /**
     * Expected validation result.
     */
    public record ValidationExpectation(
        String phase,
        String rule,
        String expect,
        List<ExpectedDiagnostic> diagnostics
    ) {
        public boolean expectsValid() {
            return "valid".equals(expect);
        }

        public boolean expectsInvalid() {
            return "invalid".equals(expect);
        }
    }

    /**
     * Expected diagnostic in a test case.
     */
    public record ExpectedDiagnostic(
        String code,
        String message,
        String severity,
        String coordinate,
        String schema,
        String member
    ) {}
}
