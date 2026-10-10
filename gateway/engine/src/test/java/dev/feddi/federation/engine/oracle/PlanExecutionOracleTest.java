package dev.feddi.federation.engine.oracle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.feddi.federation.engine.benchmark.SimulatedSubgraphClient;
import dev.feddi.federation.engine.compose.Composer;
import dev.feddi.federation.engine.compose.CompositionResult;
import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.executor.Executor;
import dev.feddi.federation.engine.executor.SubgraphClient;
import dev.feddi.federation.engine.planner.ExecutionPlan;
import dev.feddi.federation.engine.planner.OperationPlanner;
import dev.feddi.federation.engine.query.Operation;
import dev.feddi.federation.engine.query.OperationNormalizer;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.language.Document;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.OperationDefinition;
import graphql.language.Type;
import graphql.language.TypeName;
import graphql.language.VariableDefinition;
import graphql.parser.Parser;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLSchema;
import graphql.validation.ValidationError;
import graphql.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks that feddi returns the right data, independent of how it plans: every planner fixture
 * ({@code schemas/<case>/planning/*.yaml}) and every imported Fusion planner test
 * ({@code fusion-planning/}) runs through the planner and executor against executable source
 * schemas with {@link DeterministicData}, and the result is compared with the same query against
 * a monolith (the composite schema with the same data).
 *
 * <p>Every result is recorded in {@code plan-oracle-baseline.yaml}; a result that differs from the
 * baseline fails the test in either direction. After an intended change, run
 * {@code ./gradlew :engine:test -PupdatePlanOracle} and commit the baseline.
 *
 * <p>Debugging: list case ids (as in the baseline) in {@code engine/build/oracle-debug-cases.txt}; the
 * test then writes their plan, the monolith's data and feddi's data to {@code engine/build/oracle-debug.txt}.
 */
class PlanExecutionOracleTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Map<String, String> OUTCOMES = new TreeMap<>();
    private static final Path DEBUG_CASES = Path.of("build/oracle-debug-cases.txt");
    private static final StringBuilder DEBUG = new StringBuilder();

    private record Case(String id, Path schemaFile, String query, Map<String, Object> variables) {
    }

    @TestFactory
    Stream<DynamicTest> feddiReturnsTheMonolithsData() throws Exception {
        Map<String, String> baseline = loadBaseline();
        return cases().stream().map(c -> DynamicTest.dynamicTest(c.id(), () -> {
            String outcome = run(c);
            OUTCOMES.put(c.id(), outcome);
            if (!updating()) {
                assertThat(outcome)
                    .as("%s differs from plan-oracle-baseline.yaml; if this is intended, run "
                        + "./gradlew :engine:test -PupdatePlanOracle and commit the baseline", c.id())
                    .isEqualTo(baseline.get(c.id()));
            }
        }));
    }

    @AfterAll
    static void writeBaselineAndSummary() throws IOException {
        Map<String, Long> counts = new TreeMap<>();
        OUTCOMES.values().forEach(o -> counts.merge(o.split(":")[0], 1L, Long::sum));
        System.out.println("Plan execution oracle: " + OUTCOMES.size() + " queries " + counts);
        if (!DEBUG.isEmpty()) {
            Files.writeString(Path.of("build/oracle-debug.txt"), DEBUG.toString());
        }
        if (updating() && !OUTCOMES.isEmpty()) {
            Map<String, Object> document = new LinkedHashMap<>(OUTCOMES);
            String header = "# feddi's result compared with a monolith for every planner fixture and imported Fusion\n"
                + "# test, written by PlanExecutionOracleTest (./gradlew :engine:test -PupdatePlanOracle).\n"
                + "# " + OUTCOMES.size() + " queries " + counts + "\n";
            Files.writeString(baselinePath(), header + YAML.writeValueAsString(document).replaceFirst("^---\\n", ""));
        }
    }

    private static String run(Case c) throws IOException {
        List<Composer.SubgraphInput> inputs = new ArrayList<>();
        YAML.readTree(c.schemaFile().toFile()).path("subgraphs").properties()
            .forEach(e -> inputs.add(Composer.SubgraphInput.of(e.getKey(), e.getValue().asText())));
        CompositionResult composition;
        try {
            composition = new Composer().compose(inputs);
        } catch (RuntimeException e) {
            return "composition failed";
        }
        if (!composition.isSuccess()) {
            return "composition failed";
        }

        GraphQLSchema supergraph = composition.supergraph();
        Document document = new Parser().parseDocument(c.query());
        List<ValidationError> errors = new Validator().validateDocument(supergraph, document, Locale.ENGLISH);
        if (!errors.isEmpty()) {
            return "invalid query";
        }
        Map<String, Object> variables = new LinkedHashMap<>(sampleVariables(document, supergraph));
        variables.putAll(c.variables());

        DeterministicData data = new DeterministicData(composition.subgraphs(), supergraph);
        ExecutionResult expected = GraphQL.newGraphQL(data.executable(supergraph)).build()
            .execute(ExecutionInput.newExecutionInput().query(c.query()).variables(variables).build());
        if (!expected.getErrors().isEmpty()) {
            return "monolith error: " + firstLine(expected.getErrors().get(0).getMessage());
        }

        ExecutionPlan plan;
        try {
            var normalizer = OperationNormalizer.builder(supergraph)
                .inlineFragments(true).deduplicateFields(true).sortSelections(false)
                .processSkipInclude(true).build();
            plan = new OperationPlanner(composition.graph()).plan(Operation.parse(c.query(), normalizer));
        } catch (RuntimeException e) {
            return "planning failed";
        }

        Map<String, SubgraphClient> clients = new LinkedHashMap<>();
        for (Subgraph subgraph : composition.subgraphs()) {
            clients.put(subgraph.name(), new SimulatedSubgraphClient(data.executable(subgraph.schema()), 0));
        }
        ExecutionResult actual;
        try {
            actual = new Executor(clients, supergraph).execute(plan, variables).block(Duration.ofSeconds(30));
        } catch (RuntimeException e) {
            return "execution failed: " + firstLine(String.valueOf(e.getMessage()));
        }
        if (actual == null) {
            return "execution failed: no result";
        }
        if (!actual.getErrors().isEmpty()) {
            return "feddi error: " + firstLine(actual.getErrors().get(0).getMessage());
        }
        String expectedData = DeterministicData.canonical(expected.getData());
        String actualData = DeterministicData.canonical(actual.getData());
        if (Files.exists(DEBUG_CASES) && Files.readAllLines(DEBUG_CASES).stream().anyMatch(l -> l.strip().equals(c.id()))) {
            DEBUG.append("=== ").append(c.id()).append('\n');
            plan.steps().forEach(step -> DEBUG.append("step ").append(step.id()).append(' ').append(step.subgraph())
                .append(" dependsOn=").append(step.dependsOn()).append(" entityPath=").append(step.entityPath())
                .append("\n  ").append(step.toGraphQL().replace("\n", "\n  ")).append('\n'));
            DEBUG.append("expected: ").append(expectedData).append("\nactual:   ").append(actualData).append("\n\n");
        }
        return actualData.equals(expectedData) ? "same data" : "different data";
    }

    /** Sample values for required variables without a default, from their types. */
    private static Map<String, Object> sampleVariables(Document document, GraphQLSchema schema) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (OperationDefinition operation : document.getDefinitionsOfType(OperationDefinition.class)) {
            for (VariableDefinition variable : operation.getVariableDefinitions()) {
                if (variable.getType() instanceof NonNullType && variable.getDefaultValue() == null) {
                    values.put(variable.getName(), sample(variable.getType(), schema));
                }
            }
        }
        return values;
    }

    private static Object sample(Type<?> type, GraphQLSchema schema) {
        if (type instanceof NonNullType nonNull) {
            return sample(nonNull.getType(), schema);
        }
        if (type instanceof ListType list) {
            return List.of(sample(list.getType(), schema));
        }
        String name = ((TypeName) type).getName();
        GraphQLNamedType named = (GraphQLNamedType) schema.getType(name);
        if (named instanceof GraphQLEnumType enumType) {
            return enumType.getValues().get(0).getName();
        }
        if (named instanceof GraphQLInputObjectType input) {
            Map<String, Object> value = new LinkedHashMap<>();
            input.getFieldDefinitions().stream()
                .filter(f -> f.getType() instanceof GraphQLNonNull && !f.hasSetDefaultValue())
                .forEach(f -> value.put(f.getName(), sample(toAst(f.getType()), schema)));
            return value;
        }
        return switch (name) {
            case "Boolean" -> true;
            case "Int" -> 1;
            case "Float" -> 1.5;
            case "String" -> "x";
            default -> "s123456";
        };
    }

    private static Type<?> toAst(GraphQLInputType type) {
        if (type instanceof GraphQLNonNull nonNull) {
            return NonNullType.newNonNullType(toAst((GraphQLInputType) nonNull.getWrappedType())).build();
        }
        if (type instanceof GraphQLList list) {
            return ListType.newListType(toAst((GraphQLInputType) list.getWrappedType())).build();
        }
        return TypeName.newTypeName(((GraphQLNamedType) type).getName()).build();
    }

    private static List<Case> cases() throws IOException, URISyntaxException {
        List<Case> cases = new ArrayList<>();
        Path schemas = resource("schemas");
        try (Stream<Path> dirs = Files.list(schemas)) {
            for (Path dir : dirs.sorted().toList()) {
                Path planning = dir.resolve("planning");
                if (!Files.isDirectory(planning) || !Files.exists(dir.resolve("schema.yaml"))) {
                    continue;
                }
                try (Stream<Path> files = Files.list(planning)) {
                    for (Path file : files.filter(f -> f.toString().endsWith(".yaml")).sorted().toList()) {
                        JsonNode test = YAML.readTree(file.toFile());
                        Map<String, Object> variables = test.has("variables")
                            ? YAML.convertValue(test.get("variables"), Map.class) : Map.of();
                        cases.add(new Case("schemas/" + dir.getFileName() + "/" + file.getFileName().toString()
                            .replace(".yaml", ""), dir.resolve("schema.yaml"), test.path("query").asText(),
                            variables == null ? Map.of() : variables));
                    }
                }
            }
        }
        Path fusion = resource("fusion-planning");
        try (Stream<Path> files = Files.walk(fusion)) {
            for (Path query : files.filter(p -> p.getFileName().toString().equals("query.graphql")).sorted().toList()) {
                Path dir = query.getParent();
                cases.add(new Case("fusion/" + fusion.relativize(dir).toString().replace('\\', '/'),
                    dir.resolve("schema.yaml"), Files.readString(query), Map.of()));
            }
        }
        return cases;
    }

    private static Map<String, String> loadBaseline() throws IOException {
        Map<String, String> baseline = new TreeMap<>();
        Path path = baselinePath();
        if (Files.exists(path)) {
            YAML.readTree(path.toFile()).properties().forEach(e -> baseline.put(e.getKey(), e.getValue().asText()));
        }
        return baseline;
    }

    private static boolean updating() {
        return Boolean.parseBoolean(System.getProperty("planOracle.update"));
    }

    private static Path baselinePath() {
        return Path.of(Objects.requireNonNull(System.getProperty("planOracle.baseline"),
            "planOracle.baseline is set by the engine's test task"));
    }

    private static Path resource(String name) throws URISyntaxException {
        return Path.of(Objects.requireNonNull(PlanExecutionOracleTest.class.getClassLoader().getResource(name)).toURI());
    }

    private static String firstLine(String text) {
        String line = text.strip().lines().findFirst().orElse("");
        return line.length() > 140 ? line.substring(0, 140) : line;
    }
}
