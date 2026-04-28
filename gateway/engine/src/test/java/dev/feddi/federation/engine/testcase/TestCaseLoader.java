package dev.feddi.federation.engine.testcase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.feddi.federation.engine.graph.GraphBuilder;
import dev.feddi.federation.engine.compose.SchemaMerger;
import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.graph.Graph;
import dev.feddi.federation.engine.planner.ExecutionPlan;
import dev.feddi.federation.engine.planner.ExecutionStep;
import dev.feddi.federation.engine.query.Operation;
import dev.feddi.federation.engine.query.OperationNormalizer;
import dev.feddi.federation.engine.parser.FieldSelectionMap.SelectedValue;
import dev.feddi.federation.engine.parser.FieldSelectionMapParser;
import dev.feddi.federation.engine.compose.SubgraphParser;
import graphql.language.Definition;
import graphql.language.Document;
import graphql.language.OperationDefinition;
import graphql.parser.Parser;
import graphql.schema.GraphQLSchema;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads test cases from YAML files.
 *
 * Expected folder structure:
 * schemas/
 *   schema_name/
 *     schema.yaml       # Schema definition with subgraph SDLs
 *     planning/
 *       test1.yaml      # Planning test
 *       test2.yaml      # Planning test
 *     executions/
 *       test1.yaml      # Execution test
 *
 * Schema YAML format:
 * name: "Schema Name"
 * description: "Optional description"
 * subgraphs:
 *   subgraph_name: |
 *     type Query { ... }
 *     type Foo @key(fields: "id") { ... }
 * supergraph: |
 *   type Query { ... }
 *   type Foo { ... }
 */
public final class TestCaseLoader {

    private final ObjectMapper mapper;
    private final SubgraphParser subgraphParser;
    private final GraphBuilder graphBuilder;
    private final SchemaMerger schemaMerger;
    private final Parser parser;

    public TestCaseLoader() {
        this.mapper = new ObjectMapper(new YAMLFactory());
        this.subgraphParser = new SubgraphParser();
        this.graphBuilder = new GraphBuilder();
        this.schemaMerger = new SchemaMerger();
        this.parser = new Parser();
    }

    private OperationNormalizer createNormalizer(GraphQLSchema schema) {
        return OperationNormalizer.builder(schema)
            .inlineFragments(true)
            .deduplicateFields(true)
            .sortSelections(false)  // Don't sort to preserve query field order
            .processSkipInclude(true)  // Evaluate literal @skip/@include at planning time
            .build();
    }
    
    // ==================== Schema Loading ====================
    
    /**
     * Loads a schema definition from a file path.
     */
    public SchemaDefinition loadSchema(Path path) throws IOException {
        try (InputStream is = Files.newInputStream(path)) {
            return loadSchema(is);
        }
    }
    
    /**
     * Loads a schema definition from an input stream.
     */
    public SchemaDefinition loadSchema(InputStream inputStream) throws IOException {
        JsonNode root = mapper.readTree(inputStream);
        return parseSchemaDefinition(root);
    }
    
    /**
     * Loads a schema definition from classpath.
     */
    public SchemaDefinition loadSchemaFromClasspath(String resourcePath) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Schema not found: " + resourcePath);
            }
            return loadSchema(is);
        }
    }
    
    private SchemaDefinition parseSchemaDefinition(JsonNode root) {
        String name = root.path("name").asText("unnamed");
        String description = root.path("description").asText(null);

        // Parse subgraphs and collect both the graph and the schemas
        List<Subgraph> subgraphs = parseSubgraphs(root);
        Graph graph = graphBuilder.build(subgraphs);

        // Build map of subgraph name to schema
        Map<String, GraphQLSchema> subgraphSchemas = new HashMap<>();
        List<GraphQLSchema> schemas = new ArrayList<>();
        for (Subgraph subgraph : subgraphs) {
            subgraphSchemas.put(subgraph.name(), subgraph.schema());
            schemas.add(subgraph.schema());
        }

        // Merge all subgraph schemas into a supergraph for normalization
        GraphQLSchema supergraphSchema = schemaMerger.mergeAll(schemas);

        return new SchemaDefinition(name, description, graph, subgraphs, subgraphSchemas, supergraphSchema);
    }

    // ==================== Supergraph Expectation Loading ====================

    /**
     * Loads supergraph expectation from a schema file path.
     * Returns null if no supergraph section is defined.
     */
    public SupergraphExpectation loadSupergraphExpectation(Path path) throws IOException {
        try (InputStream is = Files.newInputStream(path)) {
            return loadSupergraphExpectation(is);
        }
    }

    /**
     * Loads supergraph expectation from an input stream.
     * Returns null if no supergraph section is defined.
     */
    public SupergraphExpectation loadSupergraphExpectation(InputStream inputStream) throws IOException {
        JsonNode root = mapper.readTree(inputStream);
        return parseSupergraphExpectation(root);
    }

    private SupergraphExpectation parseSupergraphExpectation(JsonNode root) {
        JsonNode supergraphNode = root.path("supergraph");
        if (supergraphNode.isMissingNode() || supergraphNode.isNull()) {
            return null;
        }

        String sdl = supergraphNode.asText();
        if (sdl == null || sdl.isBlank()) {
            return null;
        }

        return new SupergraphExpectation(sdl);
    }
    
    // ==================== Query Test Loading ====================
    
    /**
     * Loads a query test from a file path.
     */
    public QueryTest loadQueryTest(Path path, GraphQLSchema supergraphSchema) throws IOException {
        try (InputStream is = Files.newInputStream(path)) {
            return loadQueryTest(is, supergraphSchema);
        }
    }

    /**
     * Loads a query test from an input stream.
     */
    public QueryTest loadQueryTest(InputStream inputStream, GraphQLSchema supergraphSchema) throws IOException {
        JsonNode root = mapper.readTree(inputStream);
        return parseQueryTest(root, supergraphSchema);
    }

    /**
     * Loads a query test from classpath.
     */
    public QueryTest loadQueryTestFromClasspath(String resourcePath, GraphQLSchema supergraphSchema) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Query test not found: " + resourcePath);
            }
            return loadQueryTest(is, supergraphSchema);
        }
    }

    private QueryTest parseQueryTest(JsonNode node, GraphQLSchema supergraphSchema) {
        String name = node.path("name").asText("unnamed");
        String description = node.path("description").asText(null);
        OperationNormalizer normalizer = createNormalizer(supergraphSchema);
        Operation operation = Operation.parse(node.path("query").asText(), normalizer);
        ExecutionPlan expectedPlan = node.has("expectedPlan")
            ? parseExpectedPlan(node.path("expectedPlan"))
            : null;
        return new QueryTest(name, description, operation, expectedPlan);
    }
    
    // ==================== Execution Test Loading ====================
    
    /**
     * Loads an execution test from a file path.
     */
    public ExecutionTest loadExecutionTest(Path path) throws IOException {
        try (InputStream is = Files.newInputStream(path)) {
            return loadExecutionTest(is);
        }
    }
    
    /**
     * Loads an execution test from an input stream.
     */
    public ExecutionTest loadExecutionTest(InputStream inputStream) throws IOException {
        JsonNode root = mapper.readTree(inputStream);
        return parseExecutionTest(root);
    }
    
    private ExecutionTest parseExecutionTest(JsonNode node) {
        String name = node.path("name").asText("unnamed");
        String description = node.path("description").asText(null);
        String query = node.path("query").asText();

        // Parse variables
        Map<String, Object> variables = new LinkedHashMap<>();
        JsonNode varsNode = node.path("variables");
        if (varsNode.isObject()) {
            variables = parseJsonToMap(varsNode);
        }

        // Parse subgraph calls
        List<SubgraphCall> subgraphCalls = new ArrayList<>();
        JsonNode callsNode = node.path("subgraphCalls");
        if (callsNode.isArray()) {
            for (JsonNode callNode : callsNode) {
                subgraphCalls.add(parseSubgraphCall(callNode));
            }
        }

        // Parse finish order (optional)
        String finishOrder = null;
        JsonNode finishOrderNode = node.path("finishOrder");
        if (!finishOrderNode.isMissingNode() && !finishOrderNode.isNull()) {
            finishOrder = finishOrderNode.asText();
        }

        // Parse timeout (optional)
        Long timeoutMs = null;
        JsonNode timeoutNode = node.path("timeoutMs");
        if (!timeoutNode.isMissingNode() && !timeoutNode.isNull()) {
            timeoutMs = timeoutNode.asLong();
        }

        // Parse expected response
        Map<String, Object> expectedResponse = new LinkedHashMap<>();
        JsonNode responseNode = node.path("expectedResponse");
        if (responseNode.isObject()) {
            expectedResponse = parseJsonToMap(responseNode);
        }

        // Parse skip flag (optional)
        Boolean skip = null;
        JsonNode skipNode = node.path("skip");
        if (!skipNode.isMissingNode() && !skipNode.isNull()) {
            skip = skipNode.asBoolean();
        }

        return new ExecutionTest(name, description, query, variables, subgraphCalls, finishOrder, timeoutMs, expectedResponse, skip);
    }
    
    private SubgraphCall parseSubgraphCall(JsonNode node) {
        // Parse optional id
        String id = null;
        JsonNode idNode = node.path("id");
        if (!idNode.isMissingNode() && !idNode.isNull()) {
            id = idNode.asText();
        }

        String subgraph = node.path("subgraph").asText();
        String operation = node.path("operation").asText();

        Map<String, Object> variables = new LinkedHashMap<>();
        JsonNode varsNode = node.path("variables");
        if (varsNode.isObject()) {
            variables = parseJsonToMap(varsNode);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        JsonNode responseNode = node.path("response");
        if (responseNode.isObject()) {
            response = parseJsonToMap(responseNode);
        }

        // Parse optional delay (for timeout testing)
        Long delayMs = null;
        JsonNode delayNode = node.path("delayMs");
        if (!delayNode.isMissingNode() && !delayNode.isNull()) {
            // Handle special value "infinite" for timeout testing
            if (delayNode.isTextual() && delayNode.asText().equalsIgnoreCase("infinite")) {
                delayMs = Long.MAX_VALUE;
            } else {
                delayMs = delayNode.asLong();
            }
        }

        // Parse optional failWithError (for simulating subgraph call failures)
        String failWithError = null;
        JsonNode failNode = node.path("failWithError");
        if (!failNode.isMissingNode() && !failNode.isNull()) {
            failWithError = failNode.asText();
        }

        return new SubgraphCall(id, subgraph, operation, variables, response, delayMs, failWithError);
    }
    
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonToMap(JsonNode node) {
        return mapper.convertValue(node, Map.class);
    }
    
    // ==================== Graph Parsing ====================

    /**
     * Parses subgraphs from SDL definitions.
     * Uses the composition module's SubgraphParser.
     */
    private List<Subgraph> parseSubgraphs(JsonNode graphNode) {
        List<Subgraph> subgraphs = new ArrayList<>();

        JsonNode subgraphsNode = graphNode.path("subgraphs");
        if (subgraphsNode.isMissingNode() || !subgraphsNode.isObject()) {
            throw new IllegalArgumentException("Schema must have a 'subgraphs' object with SDL definitions");
        }

        subgraphsNode.fields().forEachRemaining(entry -> {
            String subgraphName = entry.getKey();
            String sdl = entry.getValue().asText();
            Subgraph subgraph = subgraphParser.parse(subgraphName, sdl);
            subgraphs.add(subgraph);
        });

        if (subgraphs.isEmpty()) {
            throw new IllegalArgumentException("Schema must have at least one subgraph");
        }

        return subgraphs;
    }
    
    // ==================== Plan Parsing ====================

    private ExecutionPlan parseExpectedPlan(JsonNode planNode) {
        List<ExecutionStep> steps = new ArrayList<>();

        JsonNode stepsArray = planNode.path("steps");
        for (JsonNode entryNode : stepsArray) {
            // Check if this is a parallel group
            JsonNode parallelNode = entryNode.path("parallel");
            if (!parallelNode.isMissingNode() && parallelNode.isArray()) {
                // Parse parallel group - all steps in this group can run in parallel
                List<ExecutionStep> parallelSteps = new ArrayList<>();
                for (JsonNode stepNode : parallelNode) {
                    parallelSteps.add(parseStepNode(stepNode, List.of()));
                }

                // Now set parallelWith for each step in the group
                for (ExecutionStep step : parallelSteps) {
                    List<Integer> parallelWith = parallelSteps.stream()
                        .filter(s -> s.id() != step.id())
                        .map(ExecutionStep::id)
                        .sorted()
                        .toList();

                    steps.add(new ExecutionStep(
                        step.id(),
                        step.subgraph(),
                        step.operation(),
                        step.dependsOn(),
                        parallelWith,
                        step.requirements(),
                        step.repeatedExecution(),
                        Set.of(),
                        Set.of()
                    ));
                }
            } else {
                // Regular single step (not in a parallel group)
                steps.add(parseStepNode(entryNode, List.of()));
            }
        }

        return ExecutionPlan.of(steps);
    }

    /**
     * Parses a single step node from the YAML.
     */
    private ExecutionStep parseStepNode(JsonNode stepNode, List<Integer> parallelWith) {
        int id = stepNode.path("id").asInt();
        String subgraph = stepNode.path("subgraph").asText();

        // Parse operation as GraphQL syntax into OperationDefinition
        String operationText = stepNode.path("operation").asText();
        OperationDefinition operation = parseOperationDefinition(operationText);

        List<Integer> dependsOn = new ArrayList<>();
        JsonNode dependsOnNode = stepNode.path("dependsOn");
        if (dependsOnNode.isArray()) {
            for (JsonNode depNode : dependsOnNode) {
                dependsOn.add(depNode.asInt());
            }
        }

        Map<String, SelectedValue> requirements = new LinkedHashMap<>();
        JsonNode reqNode = stepNode.path("requirements");
        if (reqNode.isObject()) {
            reqNode.fields().forEachRemaining(entry -> {
                String fieldSelectionMapText = entry.getValue().asText();
                SelectedValue selection = FieldSelectionMapParser.parseFieldSelectionMap(fieldSelectionMapText);
                requirements.put(entry.getKey(), selection);
            });
        }

        // Parse repeatedExecution - defaults to true if there are requirements
        boolean repeatedExecution;
        if (stepNode.has("repeatedExecution")) {
            repeatedExecution = stepNode.path("repeatedExecution").asBoolean();
        } else {
            repeatedExecution = !requirements.isEmpty();
        }

        return new ExecutionStep(id, subgraph, operation, dependsOn, parallelWith, requirements, repeatedExecution, Set.of(), Set.of());
    }
    
    /**
     * Parses a GraphQL operation string into an OperationDefinition.
     * Handles both shorthand queries "{ ... }" and named operations "query Name { ... }".
     */
    private OperationDefinition parseOperationDefinition(String operationText) {
        if (operationText == null || operationText.isBlank()) {
            // Return an empty operation
            return OperationDefinition.newOperationDefinition()
                .operation(OperationDefinition.Operation.QUERY)
                .build();
        }
        
        Document document = parser.parseDocument(operationText);
        
        for (Definition<?> definition : document.getDefinitions()) {
            if (definition instanceof OperationDefinition opDef) {
                return opDef;
            }
        }
        
        throw new IllegalArgumentException("No operation definition found in: " + operationText);
    }
}
