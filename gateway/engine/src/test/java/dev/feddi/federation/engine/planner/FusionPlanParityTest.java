package dev.feddi.federation.engine.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.feddi.federation.engine.compose.Composer;
import dev.feddi.federation.engine.compose.CompositionResult;
import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.query.Operation;
import dev.feddi.federation.engine.query.OperationNormalizer;
import graphql.parser.Parser;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plans every query of the imported Fusion planner tests ({@code fusion-planning/}, see its
 * README) with feddi and compares the plan with Fusion's: how many subgraph operations it needs
 * and how many of them run one after another (depth). Composition goes through {@link Composer},
 * like a gateway reload, so schemas that the gateway rejects count as failures.
 *
 * <p>Every result is recorded in {@code fusion-planning/parity-baseline.yaml}. A result that differs
 * from the baseline fails the test in either direction, so planner changes come with their
 * numbers: after an improvement, run {@code ./gradlew :engine:test -PupdateFusionParity} and
 * commit the updated baseline.
 */
class FusionPlanParityTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Map<String, Outcome> OUTCOMES = new TreeMap<>();

    /** feddi's result for one imported test, and Fusion's numbers for the same query. */
    record Outcome(String status, int steps, int depth, int fusionOperations, int fusionDepth) {

        boolean planned() {
            return "ok".equals(status);
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("status", status);
            map.put("steps", steps);
            map.put("depth", depth);
            map.put("fusionOperations", fusionOperations);
            map.put("fusionDepth", fusionDepth);
            return map;
        }

        static Outcome fromMap(JsonNode node) {
            return new Outcome(node.path("status").asText(), node.path("steps").asInt(), node.path("depth").asInt(),
                node.path("fusionOperations").asInt(), node.path("fusionDepth").asInt());
        }
    }

    @TestFactory
    Stream<DynamicTest> feddiPlansMatchTheRecordedBaseline() throws Exception {
        Path root = resource("fusion-planning");
        Map<String, Outcome> baseline = loadBaseline();
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.getFileName().toString().equals("query.graphql"))
                .map(Path::getParent)
                .sorted()
                .map(dir -> {
                    String id = root.relativize(dir).toString().replace('\\', '/');
                    return DynamicTest.dynamicTest(id, () -> {
                        Outcome outcome = run(dir);
                        OUTCOMES.put(id, outcome);
                        if (!updating()) {
                            assertThat(outcome)
                                .as("%s differs from parity-baseline.yaml; if this is intended, run "
                                    + "./gradlew :engine:test -PupdateFusionParity and commit the baseline", id)
                                .isEqualTo(baseline.get(id));
                        }
                    });
                })
                .toList()
                .stream();
        }
    }

    @AfterAll
    static void writeBaselineAndSummary() throws IOException {
        List<Outcome> planned = OUTCOMES.values().stream().filter(Outcome::planned).toList();
        long invalidQueries = OUTCOMES.values().stream().filter(o -> o.status().startsWith("invalid query")).count();
        long fewerOrEqualSteps = planned.stream().filter(o -> o.steps() <= o.fusionOperations()).count();
        long sameOrLowerDepth = planned.stream().filter(o -> o.depth() <= o.fusionDepth()).count();
        System.out.printf("Fusion plan parity: %d tests (%d with an invalid query), %d planned by feddi, %d with no "
                + "more steps than Fusion, %d with no more depth%n", OUTCOMES.size(), invalidQueries, planned.size(),
            fewerOrEqualSteps, sameOrLowerDepth);

        if (updating() && !OUTCOMES.isEmpty()) {
            Map<String, Object> document = new LinkedHashMap<>();
            OUTCOMES.forEach((id, outcome) -> document.put(id, outcome.toMap()));
            String header = "# feddi's plans for the imported Fusion planner tests, written by FusionPlanParityTest\n"
                + "# (./gradlew :engine:test -PupdateFusionParity). steps/depth: feddi; fusion*: Fusion's plan.\n"
                + String.format("# %d tests (%d with an invalid query), %d planned by feddi, %d with no more steps than "
                    + "Fusion, %d with no more depth%n",
                    OUTCOMES.size(), invalidQueries, planned.size(), fewerOrEqualSteps, sameOrLowerDepth);
            Files.writeString(baselinePath(), header + YAML.writeValueAsString(document).replaceFirst("^---\\n", ""));
        }
    }

    private static Outcome run(Path dir) throws IOException {
        JsonNode fusionPlan = YAML.readTree(dir.resolve("fusion-plan.yaml").toFile());
        int fusionOperations = 0;
        Map<Integer, List<Integer>> fusionDependencies = new HashMap<>();
        for (JsonNode node : fusionPlan.path("nodes")) {
            String type = node.path("type").asText();
            if (type.equals("Operation") || type.equals("OperationBatch") || type.equals("Node")) {
                fusionOperations++;
            }
            List<Integer> dependencies = new ArrayList<>();
            node.path("dependencies").forEach(d -> dependencies.add(d.path("id").asInt()));
            fusionDependencies.put(node.path("id").asInt(), dependencies);
        }
        int fusionDepth = depth(fusionDependencies);

        CompositionResult composition;
        try {
            List<Composer.SubgraphInput> inputs = new ArrayList<>();
            YAML.readTree(dir.resolve("schema.yaml").toFile()).path("subgraphs").properties()
                .forEach(e -> inputs.add(Composer.SubgraphInput.of(e.getKey(), e.getValue().asText())));
            composition = new Composer().compose(inputs);
        } catch (RuntimeException e) {
            return new Outcome("composition: " + message(e), 0, 0, fusionOperations, fusionDepth);
        }
        if (!composition.isSuccess()) {
            String error = composition.validationResult().errors().stream().findFirst()
                .map(d -> d.code() + ": " + d.message()).orElse("failed");
            return new Outcome("composition: " + firstLine(error), 0, 0, fusionOperations, fusionDepth);
        }

        String query = Files.readString(dir.resolve("query.graphql"));
        List<ValidationError> queryErrors = new Validator().validateDocument(composition.supergraph(),
            new Parser().parseDocument(query), Locale.ENGLISH);
        if (!queryErrors.isEmpty()) {
            // Fusion's planner tests do not validate the query; a gateway rejects these requests.
            return new Outcome("invalid query: " + firstLine(queryErrors.get(0).getMessage()), 0, 0,
                fusionOperations, fusionDepth);
        }

        ExecutionPlan plan;
        try {
            var normalizer = OperationNormalizer.builder(composition.supergraph())
                .inlineFragments(true).deduplicateFields(true).sortSelections(false)
                .processSkipInclude(true).build();
            plan = new OperationPlanner(composition.graph()).plan(Operation.parse(query, normalizer));
        } catch (RuntimeException e) {
            return new Outcome("planning: " + message(e), 0, 0, fusionOperations, fusionDepth);
        }

        String invalid = invalidStep(plan, composition.subgraphs());
        if (invalid != null) {
            return new Outcome("invalid subgraph operation: " + invalid, 0, 0, fusionOperations, fusionDepth);
        }

        Map<Integer, List<Integer>> dependencies = new HashMap<>();
        int steps = 0;
        for (ExecutionStep step : plan.steps()) {
            if (!"$introspection".equals(step.subgraph())) {
                steps++;
            }
            dependencies.put(step.id(), step.dependsOn());
        }
        return new Outcome("ok", steps, depth(dependencies), fusionOperations, fusionDepth);
    }

    /** First step whose operation is not valid against its subgraph schema, or null. */
    private static String invalidStep(ExecutionPlan plan, List<Subgraph> subgraphs) {
        Map<String, GraphQLSchema> schemas = new HashMap<>();
        subgraphs.forEach(s -> schemas.put(s.name(), s.schema()));
        Validator validator = new Validator();
        for (ExecutionStep step : plan.steps()) {
            GraphQLSchema subgraphSchema = schemas.get(step.subgraph());
            if (subgraphSchema == null) {
                continue;
            }
            List<ValidationError> errors = validator.validateDocument(subgraphSchema,
                new Parser().parseDocument(step.toGraphQL()), Locale.ENGLISH);
            if (!errors.isEmpty()) {
                return "step " + step.id() + " (" + step.subgraph() + "): " + firstLine(errors.get(0).getMessage());
            }
        }
        return null;
    }

    /** Longest dependency chain, counted in nodes. */
    static int depth(Map<Integer, List<Integer>> dependencies) {
        Map<Integer, Integer> memo = new HashMap<>();
        int max = 0;
        for (Integer id : dependencies.keySet()) {
            max = Math.max(max, depth(id, dependencies, memo));
        }
        return max;
    }

    private static int depth(Integer id, Map<Integer, List<Integer>> dependencies, Map<Integer, Integer> memo) {
        Integer known = memo.get(id);
        if (known != null) {
            return known;
        }
        memo.put(id, 0); // guards against cycles
        int deepest = 0;
        for (Integer dependency : dependencies.getOrDefault(id, List.of())) {
            deepest = Math.max(deepest, depth(dependency, dependencies, memo));
        }
        memo.put(id, deepest + 1);
        return deepest + 1;
    }

    private static Map<String, Outcome> loadBaseline() throws IOException {
        Map<String, Outcome> baseline = new TreeMap<>();
        Path path = baselinePath();
        if (Files.exists(path)) {
            YAML.readTree(path.toFile()).properties().forEach(e -> baseline.put(e.getKey(), Outcome.fromMap(e.getValue())));
        }
        return baseline;
    }

    private static boolean updating() {
        return Boolean.parseBoolean(System.getProperty("fusionParity.update"));
    }

    private static Path baselinePath() {
        return Path.of(Objects.requireNonNull(System.getProperty("fusionParity.baseline"),
            "fusionParity.baseline is set by the engine's test task"));
    }

    private static Path resource(String name) throws URISyntaxException {
        return Path.of(Objects.requireNonNull(FusionPlanParityTest.class.getClassLoader().getResource(name)).toURI());
    }

    private static String message(Throwable e) {
        return firstLine(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }

    private static String firstLine(String text) {
        String line = text.strip().lines().findFirst().orElse("");
        return line.length() > 160 ? line.substring(0, 160) : line;
    }
}
