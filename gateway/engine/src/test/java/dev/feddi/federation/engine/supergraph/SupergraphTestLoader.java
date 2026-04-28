package dev.feddi.federation.engine.supergraph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads supergraph composition test cases from YAML files.
 * 
 * Expected YAML format for positive tests (composition should succeed):
 * <pre>
 * name: "Test Name"
 * description: "Optional description"
 * subgraphs:
 *   subgraph_name: |
 *     type Query { ... }
 * supergraph: |
 *   type Query { ... }
 * </pre>
 * 
 * Expected YAML format for negative tests (validation should fail):
 * <pre>
 * name: "Test Name"
 * description: "Optional description"
 * subgraphs:
 *   subgraph_name: |
 *     type Query { ... }
 * error: "ERROR_CODE"
 * </pre>
 */
public final class SupergraphTestLoader {
    
    private final ObjectMapper yamlMapper;
    
    public SupergraphTestLoader() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
    }
    
    /**
     * Loads a test case from a YAML file.
     */
    public SupergraphTestCase load(Path yamlFile) throws IOException {
        JsonNode root = yamlMapper.readTree(yamlFile.toFile());
        return parseTestCase(root, yamlFile.toString());
    }
    
    /**
     * Loads a test case from an input stream.
     */
    public SupergraphTestCase load(InputStream inputStream, String sourceName) throws IOException {
        JsonNode root = yamlMapper.readTree(inputStream);
        return parseTestCase(root, sourceName);
    }
    
    /**
     * Loads all test cases from a directory (recursive, includes subdirectories).
     */
    public List<SupergraphTestCase> loadAll(Path directory) throws IOException {
        List<SupergraphTestCase> testCases = new ArrayList<>();

        try (var stream = Files.walk(directory)) {
            stream.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                .filter(Files::isRegularFile)
                .sorted()
                .forEach(path -> {
                    try {
                        testCases.add(load(path));
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to load: " + path, e);
                    }
                });
        }

        return testCases;
    }
    
    private SupergraphTestCase parseTestCase(JsonNode root, String sourcePath) {
        String name = root.path("name").asText();
        String description = root.path("description").asText(null);
        
        // Parse subgraphs
        Map<String, String> subgraphs = new LinkedHashMap<>();
        JsonNode subgraphsNode = root.path("subgraphs");
        if (subgraphsNode.isObject()) {
            subgraphsNode.fields().forEachRemaining(entry -> {
                subgraphs.put(entry.getKey(), entry.getValue().asText());
            });
        }
        
        // Parse supergraph (for positive tests) or error (for negative tests)
        String supergraph = null;
        String error = null;
        
        JsonNode supergraphNode = root.path("supergraph");
        JsonNode errorNode = root.path("error");
        
        if (!supergraphNode.isMissingNode() && !supergraphNode.asText().isBlank()) {
            supergraph = supergraphNode.asText();
        }
        
        if (!errorNode.isMissingNode() && !errorNode.asText().isBlank()) {
            error = errorNode.asText();
        }
        
        return new SupergraphTestCase(name, description, subgraphs, supergraph, error, sourcePath);
    }
}
