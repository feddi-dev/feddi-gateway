package dev.feddi.federation.engine.oracle;

import dev.feddi.federation.engine.compose.Composer;
import dev.feddi.federation.engine.compose.CompositionResult;
import dev.feddi.federation.engine.parser.FieldSelectionMapPrinter;
import dev.feddi.federation.engine.planner.ExecutionStep;
import dev.feddi.federation.engine.planner.OperationPlanner;
import dev.feddi.federation.engine.query.Operation;
import dev.feddi.federation.engine.query.OperationNormalizer;
import graphql.language.AstPrinter;
import graphql.parser.Parser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The planner on graphs nobody wrote by hand: random composite schemas (entities spread over source schemas, shared
 * and owned fields, relations, root fields in several schemas, requirements, ...) with random operations, each
 * checked against the monolith like {@link PlanExecutionOracleTest}. A seed always generates the same graph and
 * operations; a failure names the seed, and the schemas and operation are in the message to reproduce it.
 *
 * <p>Properties (Gradle: {@code -PrandomGraphSeeds=...} etc.):
 * <ul>
 *   <li>{@code randomGraph.seeds}: how many seeds (default 150), from {@code randomGraph.firstSeed} (default 0)</li>
 *   <li>{@code randomGraph.features}: the scenarios to generate, e.g. {@code mutations,rootRequires} (default
 *       all, see {@link Feature})</li>
 *   <li>{@code randomGraph.writeFixture}: a seed whose graph, operations and current plans are written as a
 *       planner fixture to {@code build/random-fixtures/random_<seed>} (to copy into {@code schemas/})</li>
 * </ul>
 */
class RandomGraphOracleTest {

    private static final int SEEDS = Integer.getInteger("randomGraph.seeds", 150);
    private static final int FIRST_SEED = Integer.getInteger("randomGraph.firstSeed", 0);
    private static final Set<Feature> FEATURES = Feature.parse(System.getProperty("randomGraph.features", ""));
    private static final int QUERIES_PER_GRAPH = 4;

    /** The scenarios the generator can add to a graph and its operations. */
    enum Feature {
        /** A second key {@code code} on some types (nullable on some), and lookups by code only. */
        CODE_KEYS,
        /** Relations providing a field another schema owns ({@code @provides}, {@code @external}). */
        PROVIDES,
        /** Entity fields requiring a scalar another schema owns. */
        REQUIRES,
        /** Interfaces over a schema's entity types, with root fields and some interface lookups. */
        INTERFACES,
        /** Unions over a schema's entity types, with root fields. */
        UNIONS,
        /** Interface fields with {@code @require} ({@code <T1>.f | <T2>.g}) and the implementations' fields. */
        INTERFACE_REQUIRES,
        /** Root fields requiring another schema's root field. */
        ROOT_REQUIRES,
        /** A schema taking an owned scalar field over with {@code @override}. */
        OVERRIDE,
        /** A schema's lookups below an argument-less root field ({@code lookups0 { e1ById }}). */
        NESTED_LOOKUPS,
        /** Mutation fields in several schemas, and operations with several of them. */
        MUTATIONS,
        /** {@code @skip} and {@code @include} on fields and fragments, with literals and a variable. */
        SKIP_INCLUDE;

        static Set<Feature> parse(String names) {
            if (names.isBlank()) {
                return EnumSet.allOf(Feature.class);
            }
            Set<Feature> features = EnumSet.noneOf(Feature.class);
            for (String name : names.split(",")) {
                String wanted = name.strip().replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase();
                features.add(Arrays.stream(values()).filter(f -> f.name().equals(wanted)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown random graph feature '" + name
                        + "', known: " + Arrays.toString(values()))));
            }
            return features;
        }
    }

    @org.junit.jupiter.api.AfterAll
    static void writeDebugOutput() throws IOException {
        // Cases listed in build/oracle-debug-cases.txt (e.g. random/115/0) are written like PlanExecutionOracleTest's
        PlanExecutionOracleTest.writeDebugOutput();
    }

    @TestFactory
    Stream<DynamicTest> randomGraphsReturnTheMonolithsData() {
        return IntStream.range(FIRST_SEED, FIRST_SEED + SEEDS).mapToObj(seed -> DynamicTest.dynamicTest("seed " + seed, () -> {
            RandomGraph graph = new RandomGraph(new Random(seed), FEATURES);
            List<Composer.SubgraphInput> inputs = graph.inputs();
            CompositionResult composition = new Composer().compose(inputs);
            assertThat(composition.isSuccess())
                .as("seed %d does not compose: %s%n%s", seed, composition.validationResult().errors(), graph.sdl())
                .isTrue();
            for (int i = 0; i < QUERIES_PER_GRAPH; i++) {
                String query = graph.query();
                String outcome = PlanExecutionOracleTest.check("random/" + seed + "/" + i, inputs, query, Map.of());
                assertThat(outcome).as("seed %d, query:%n%s%n%s", seed, query, graph.sdl()).isEqualTo("same data");
            }
        }));
    }

    /**
     * Writes the graph and operations of {@code randomGraph.writeFixture} as a planner fixture with the plans
     * feddi makes for them today ({@code schemas/<case>/schema.yaml}, {@code planning/*.yaml}).
     */
    @Test
    void writeFixture() throws IOException {
        String seedProperty = System.getProperty("randomGraph.writeFixture", "");
        Assumptions.assumeFalse(seedProperty.isBlank(), "randomGraph.writeFixture not set");
        int seed = Integer.parseInt(seedProperty.strip());
        RandomGraph graph = new RandomGraph(new Random(seed), FEATURES);
        List<Composer.SubgraphInput> inputs = graph.inputs();
        CompositionResult composition = new Composer().compose(inputs);
        assertThat(composition.isSuccess()).as("seed %d does not compose: %s", seed,
            composition.validationResult().errors()).isTrue();

        Path dir = Path.of("build/random-fixtures/random_" + seed);
        Files.createDirectories(dir.resolve("planning"));
        StringBuilder schema = new StringBuilder("name: \"Random graph " + seed + "\"\n"
            + "description: \"Generated by RandomGraphOracleTest (seed " + seed + ", features "
            + FEATURES.stream().map(Enum::name).collect(Collectors.joining(", ")) + ").\"\n\nsubgraphs:\n");
        for (Composer.SubgraphInput input : inputs) {
            schema.append("  ").append(input.name()).append(": |\n").append(indent(input.sdl(), "    "));
        }
        Files.writeString(dir.resolve("schema.yaml"), schema.toString());

        var normalizer = OperationNormalizer.builder(composition.supergraph()).inlineFragments(true)
            .deduplicateFields(true).sortSelections(false).build();
        for (int i = 0; i < QUERIES_PER_GRAPH; i++) {
            String query = graph.query();
            StringBuilder planning = new StringBuilder("name: \"Random graph " + seed + ", operation " + i + "\"\n"
                + "description: \"The plan feddi made when the fixture was written.\"\n\nquery: |\n"
                + indent(query, "  ") + "\nexpectedPlan:\n  steps:\n");
            // $flag's default value decides its conditions
            var evaluating = normalizer.withConditionValues(
                OperationNormalizer.conditionValues(new Parser().parseDocument(query), Map.of()));
            for (ExecutionStep step : new OperationPlanner(composition.graph())
                    .plan(Operation.parse(query, evaluating)).steps()) {
                planning.append("    - id: ").append(step.id()).append('\n')
                    .append("      subgraph: ").append(step.subgraph()).append('\n')
                    .append("      operation: \"").append(AstPrinter.printAstCompact(step.operation())
                        .replace("\\", "\\\\").replace("\"", "\\\"")).append("\"\n")
                    .append("      dependsOn: ").append(step.dependsOn()).append('\n');
                if (!step.requirements().isEmpty()) {
                    planning.append("      requirements:\n");
                    step.requirements().forEach((name, value) -> planning.append("        ").append(name)
                        .append(": \"").append(FieldSelectionMapPrinter.print(value).replace("\"", "\\\""))
                        .append("\"\n"));
                }
                planning.append("      repeatedExecution: ").append(step.repeatedExecution()).append("\n\n");
            }
            Files.writeString(dir.resolve("planning/" + String.format("%02d", i + 1) + "_operation.yaml"),
                planning.toString().stripTrailing() + "\n");
        }
        System.out.println("Wrote " + dir.toAbsolutePath());
    }

    private static String indent(String text, String prefix) {
        return text.lines().map(line -> line.isEmpty() ? "" : prefix + line)
            .collect(Collectors.joining("\n", "", "\n"));
    }

    /** A random set of source schemas and operations against their composite schema. */
    private static final class RandomGraph {
        private final Random random;
        private final Set<Feature> features;
        private final int subgraphCount;
        private final int typeCount;
        // per subgraph: type -> field definitions (SDL lines)
        private final List<Map<String, List<String>>> types = new ArrayList<>();
        private final List<List<String>> rootFields = new ArrayList<>();
        // composite view for queries: type -> field -> type name ("" for scalars), list flag in name suffix "[]"
        private final Map<String, Map<String, String>> composite = new LinkedHashMap<>();
        // root fields as "name:Type" ("name:" for a scalar)
        private final List<String> rootComposite = new ArrayList<>();
        // per subgraph: abstract type definitions (SDL) and which entity types implement which interface there
        private final List<List<String>> abstracts = new ArrayList<>();
        private final List<Map<String, List<String>>> implementations = new ArrayList<>();
        // composite: abstract type -> possible entity types (from the one subgraph that defines it)
        private final Map<String, List<String>> members = new LinkedHashMap<>();
        // interface -> its fields besides id (SDL lines), selectable on the interface
        private final Map<String, List<String>> interfaceFields = new LinkedHashMap<>();
        // types with a second key "code", and per subgraph the types it looks up by code only
        private final Set<String> codeKeyed = new LinkedHashSet<>();
        private final Set<String> nullableCode = new LinkedHashSet<>();
        private final List<Set<String>> codeOnlyLookups = new ArrayList<>();
        // per subgraph: interfaces with a lookup returning them
        private final List<Set<String>> abstractLookups = new ArrayList<>();
        // subgraphs whose lookups are nested below an argument-less root field
        private final Set<Integer> nestedLookups = new LinkedHashSet<>();
        // per subgraph: mutation fields (SDL lines); composite mutation fields as "name:Type"
        private final List<List<String>> mutationFields = new ArrayList<>();
        private final List<String> mutationComposite = new ArrayList<>();

        RandomGraph(Random random, Set<Feature> features) {
            this.random = random;
            this.features = features;
            this.subgraphCount = 2 + random.nextInt(3);
            this.typeCount = 2 + random.nextInt(3);
            for (int s = 0; s < subgraphCount; s++) {
                types.add(new LinkedHashMap<>());
                rootFields.add(new ArrayList<>());
                abstracts.add(new ArrayList<>());
                implementations.add(new LinkedHashMap<>());
                codeOnlyLookups.add(new LinkedHashSet<>());
                abstractLookups.add(new LinkedHashSet<>());
                mutationFields.add(new ArrayList<>());
            }
            build();
        }

        private boolean on(Feature feature) {
            return features.contains(feature);
        }

        private void build() {
            // Each entity type is defined (with its key and a lookup) in one or more source schemas
            for (int t = 0; t < typeCount; t++) {
                String type = "E" + t;
                composite.put(type, new LinkedHashMap<>());
                composite.get(type).put("id", "");
                for (int s : someSubgraphs()) {
                    define(s, type);
                }
            }
            for (int t = 0; t < typeCount; t++) {
                String type = "E" + t;
                List<Integer> definers = definers(type);
                // Scalar fields, owned by one definer or shared by two
                int scalars = 1 + random.nextInt(4);
                for (int i = 0; i < scalars; i++) {
                    String field = "f" + t + "_" + i;
                    String scalar = random.nextBoolean() ? "String" : "Int";
                    if (definers.size() > 1 && random.nextInt(5) == 0) {
                        for (int s : pick(definers, 2)) {
                            types.get(s).get(type).add(field + ": " + scalar + " @shareable");
                        }
                    } else {
                        types.get(s(definers)).get(type).add(field + ": " + scalar);
                    }
                    composite.get(type).put(field, "");
                }
                // Relations to other entities, in a definer that then also knows the target's key
                int relations = random.nextInt(3);
                for (int i = 0; i < relations; i++) {
                    String target = "E" + random.nextInt(typeCount);
                    boolean list = random.nextBoolean();
                    String field = "r" + t + "_" + i;
                    int s = s(definers);
                    define(s, target);
                    types.get(s).get(type).add(field + ": " + (list ? "[" + target + "]" : target));
                    composite.get(type).put(field, target + (list ? "[]" : ""));
                }
            }
            if (on(Feature.CODE_KEYS)) {
                addCodeKeys();
            }
            if (on(Feature.PROVIDES)) {
                addProvides();
            }
            if (on(Feature.REQUIRES)) {
                addRequires();
            }
            addAbstractTypes();
            if (on(Feature.INTERFACE_REQUIRES)) {
                addInterfaceRequires();
            }
            // Root fields: at least one per schema that defines types
            for (int s = 0; s < subgraphCount; s++) {
                List<String> defined = new ArrayList<>(types.get(s).keySet());
                if (defined.isEmpty()) {
                    continue;
                }
                int roots = 1 + random.nextInt(2);
                for (int i = 0; i < roots; i++) {
                    String type = defined.get(random.nextInt(defined.size()));
                    boolean list = random.nextBoolean();
                    String name = "q" + s + "_" + i;
                    rootFields.get(s).add(name + ": " + (list ? "[" + type + "]" : type));
                    rootComposite.add(name + ":" + type + (list ? "[]" : ""));
                }
            }
            if (on(Feature.ROOT_REQUIRES)) {
                addRootRequires();
            }
            if (on(Feature.OVERRIDE)) {
                addOverrides();
            }
            if (on(Feature.NESTED_LOOKUPS)) {
                for (int s = 0; s < subgraphCount; s++) {
                    if (!types.get(s).isEmpty() && random.nextInt(3) == 0) {
                        nestedLookups.add(s);
                    }
                }
            }
            if (on(Feature.MUTATIONS)) {
                addMutations();
            }
        }

        /** A second key "code" on some types, defined everywhere the type is; some schemas look it up by code only. */
        private void addCodeKeys() {
            for (int t = 0; t < typeCount; t++) {
                String type = "E" + t;
                if (random.nextInt(3) != 0) {
                    continue;
                }
                codeKeyed.add(type);
                composite.get(type).put("code", "");
                // Nullable on some types: a key selected next to another type's code then has another shape
                String codeType = random.nextBoolean() ? "String!" : "String";
                if (codeType.equals("String")) {
                    nullableCode.add(type);
                }
                for (int s : definers(type)) {
                    types.get(s).get(type).add(1, "code: " + codeType);
                    if (random.nextBoolean()) {
                        codeOnlyLookups.get(s).add(type);
                    }
                }
            }
        }

        /** @provides: a relation provides a field of its target that another schema owns (@external here). */
        private void addProvides() {
            for (int s = 0; s < subgraphCount; s++) {
                for (Map.Entry<String, List<String>> entry : types.get(s).entrySet()) {
                    List<String> fields = entry.getValue();
                    for (int i = 0; i < fields.size(); i++) {
                        String line = fields.get(i);
                        if (!line.matches("r\\d.*") || random.nextInt(3) != 0) {
                            continue;
                        }
                        String target = line.substring(line.indexOf(':') + 2).replace("[", "").replace("]", "");
                        String provided = ownedElsewhere(s, target, false);
                        if (provided != null && !fields.get(i).contains("@provides")) {
                            String name = fieldName(provided);
                            fields.set(i, line + " @provides(fields: \"" + name + "\")");
                            List<String> targetFields = types.get(s).get(target);
                            if (targetFields.stream().noneMatch(f -> f.startsWith(name + ":"))) {
                                targetFields.add(provided + " @external");
                            }
                        }
                    }
                }
            }
        }

        /** Requirements: a field in one definer that needs a scalar another definer owns. */
        private void addRequires() {
            for (int t = 0; t < typeCount; t++) {
                String type = "E" + t;
                List<Integer> definers = definers(type);
                if (definers.size() < 2 || random.nextInt(3) != 0) {
                    continue;
                }
                int requiring = s(definers);
                for (String line : new ArrayList<>(types.get(definers.get((definers.indexOf(requiring) + 1)
                        % definers.size())).get(type))) {
                    if (owned(line)) {
                        String required = fieldName(line);
                        String argumentType = line.substring(line.indexOf(':') + 2);
                        String field = "req" + t + "_" + required;
                        types.get(requiring).get(type).add(field + "(v: " + argumentType + " @require(field: \""
                            + required + "\")): String");
                        composite.get(type).put(field, "");
                        break;
                    }
                }
            }
        }

        /** Interfaces and unions over a schema's entity types, with root fields returning lists of them. */
        private void addAbstractTypes() {
            for (int s = 0; s < subgraphCount; s++) {
                List<String> defined = new ArrayList<>(types.get(s).keySet());
                if (defined.size() < 2) {
                    continue;
                }
                if (on(Feature.INTERFACES) && random.nextInt(3) == 0) {
                    String name = "I" + s;
                    List<String> implementing = pickTypes(defined, 2 + random.nextInt(defined.size() - 1));
                    interfaceFields.put(name, new ArrayList<>());
                    for (String t : implementing) {
                        implementations.get(s).computeIfAbsent(t, k -> new ArrayList<>()).add(name);
                    }
                    members.put(name, implementing);
                    if (random.nextBoolean()) {
                        abstractLookups.get(s).add(name);
                    }
                    rootFields.get(s).add("iq" + s + ": [" + name + "]");
                    rootComposite.add("iq" + s + ":" + name + "[]");
                }
                if (on(Feature.UNIONS) && random.nextInt(3) == 0) {
                    String name = "U" + s;
                    List<String> unionMembers = pickTypes(defined, 2 + random.nextInt(defined.size() - 1));
                    abstracts.get(s).add("union " + name + " = " + String.join(" | ", unionMembers));
                    members.put(name, unionMembers);
                    rootFields.get(s).add("uq" + s + ": [" + name + "]");
                    rootComposite.add("uq" + s + ":" + name + "[]");
                }
            }
        }

        /**
         * An interface field with @require: the interface's map selects a String field of each implementation that
         * another schema owns ({@code <E1>.f1_0 | <E2>.f2_1}); each implementation requires its own field.
         */
        private void addInterfaceRequires() {
            for (int s = 0; s < subgraphCount; s++) {
                String name = "I" + s;
                if (!interfaceFields.containsKey(name) || random.nextBoolean()) {
                    continue;
                }
                Map<String, String> required = new LinkedHashMap<>();
                for (String type : members.get(name)) {
                    String line = ownedElsewhere(s, type, true);
                    if (line == null) {
                        break;
                    }
                    required.put(type, fieldName(line));
                }
                if (required.size() < members.get(name).size()) {
                    continue;
                }
                String field = "ireq" + s;
                String map = required.entrySet().stream().map(e -> "<" + e.getKey() + ">." + e.getValue())
                    .collect(Collectors.joining(" | "));
                interfaceFields.get(name).add(field + "(v: String @require(field: \"" + map + "\")): String");
                for (Map.Entry<String, String> entry : required.entrySet()) {
                    types.get(s).get(entry.getKey())
                        .add(field + "(v: String @require(field: \"" + entry.getValue() + "\")): String");
                    composite.get(entry.getKey()).put(field, "");
                }
            }
        }

        /** Root scalar fields, and root fields that require another schema's root scalar field. */
        private void addRootRequires() {
            List<Integer> withScalar = new ArrayList<>();
            for (int s = 0; s < subgraphCount; s++) {
                if (!types.get(s).isEmpty() && random.nextBoolean()) {
                    rootFields.get(s).add("info" + s + ": String");
                    rootComposite.add("info" + s + ":");
                    withScalar.add(s);
                }
            }
            for (int s = 0; s < subgraphCount; s++) {
                int schema = s;
                List<Integer> others = withScalar.stream().filter(o -> o != schema).toList();
                if (types.get(s).isEmpty() || others.isEmpty() || random.nextInt(3) == 0) {
                    continue;
                }
                rootFields.get(s).add("rr" + s + "(v: String @require(field: \"info" + s(others) + "\")): String");
                rootComposite.add("rr" + s + ":");
            }
        }

        /**
         * @override: a definer takes an owned scalar field over from the schema that owns it. Not a field a
         * requirement or @provides references: those are resolved from the schema they name.
         */
        private void addOverrides() {
            for (int t = 0; t < typeCount; t++) {
                String type = "E" + t;
                List<Integer> definers = definers(type);
                if (definers.size() < 2 || random.nextInt(3) != 0) {
                    continue;
                }
                int owner = s(definers);
                List<String> candidates = types.get(owner).get(type).stream()
                    .filter(line -> owned(line) && !referenced(fieldName(line)))
                    .toList();
                List<Integer> takers = definers.stream().filter(s -> s != owner).toList();
                if (candidates.isEmpty()) {
                    continue;
                }
                String line = candidates.get(random.nextInt(candidates.size()));
                int taker = s(takers);
                if (types.get(taker).get(type).stream().noneMatch(f -> f.startsWith(fieldName(line) + ":")
                        || f.startsWith(fieldName(line) + "("))) {
                    types.get(taker).get(type).add(line + " @override(from: \"s" + owner + "\")");
                }
            }
        }

        /** Mutation fields returning entities in some schemas. */
        private void addMutations() {
            for (int s = 0; s < subgraphCount; s++) {
                List<String> defined = new ArrayList<>(types.get(s).keySet());
                if (defined.isEmpty() || random.nextBoolean()) {
                    continue;
                }
                int count = 1 + random.nextInt(2);
                for (int i = 0; i < count; i++) {
                    String type = defined.get(random.nextInt(defined.size()));
                    String name = "m" + s + "_" + i;
                    mutationFields.get(s).add(name + "(id: ID!): " + type);
                    mutationComposite.add(name + ":" + type);
                }
            }
        }

        /** An entity field definition that its schema resolves alone (not shared, external or taken over). */
        private static boolean owned(String line) {
            return line.startsWith("f") && !line.contains("@shareable") && !line.contains("@external")
                && !line.contains("@override");
        }

        /** Whether a @require or @provides of any schema references the field. */
        private boolean referenced(String field) {
            Pattern name = Pattern.compile("\\b" + field + "\\b");
            return types.stream().flatMap(schema -> schema.values().stream()).flatMap(List::stream)
                .filter(line -> line.contains("@require(") || line.contains("@provides("))
                .anyMatch(line -> name.matcher(line.substring(line.indexOf('@'))).find());
        }

        private static String fieldName(String line) {
            return line.substring(0, line.indexOf(':'));
        }

        private void define(int s, String type) {
            if (!types.get(s).containsKey(type)) {
                List<String> fields = new ArrayList<>();
                fields.add("id: ID!");
                types.get(s).put(type, fields);
            }
        }

        private List<Integer> definers(String type) {
            List<Integer> definers = new ArrayList<>();
            for (int s = 0; s < subgraphCount; s++) {
                if (types.get(s).containsKey(type)) {
                    definers.add(s);
                }
            }
            return definers;
        }

        private Set<Integer> someSubgraphs() {
            Set<Integer> chosen = new LinkedHashSet<>();
            chosen.add(random.nextInt(subgraphCount));
            for (int s = 0; s < subgraphCount; s++) {
                if (random.nextInt(3) == 0) {
                    chosen.add(s);
                }
            }
            return chosen;
        }

        private int s(List<Integer> options) {
            return options.get(random.nextInt(options.size()));
        }

        /**
         * A scalar field definition of {@code type} owned (not shared) by a schema other than {@code s}, which
         * {@code s} does not define; with {@code onlyString}, of type String.
         */
        private String ownedElsewhere(int s, String type, boolean onlyString) {
            for (int other = 0; other < subgraphCount; other++) {
                if (other == s || !types.get(other).containsKey(type)) {
                    continue;
                }
                for (String line : types.get(other).get(type)) {
                    if (owned(line) && (!onlyString || line.endsWith(": String"))) {
                        String name = fieldName(line);
                        if (types.get(s).get(type).stream().noneMatch(f -> f.startsWith(name + ":"))) {
                            return line;
                        }
                    }
                }
            }
            return null;
        }

        private List<String> pickTypes(List<String> options, int count) {
            List<String> copy = new ArrayList<>(options);
            java.util.Collections.shuffle(copy, random);
            return copy.subList(0, Math.min(count, copy.size()));
        }

        private List<Integer> pick(List<Integer> options, int count) {
            List<Integer> copy = new ArrayList<>(options);
            java.util.Collections.shuffle(copy, random);
            return copy.subList(0, count);
        }

        List<Composer.SubgraphInput> inputs() {
            List<Composer.SubgraphInput> inputs = new ArrayList<>();
            for (int s = 0; s < subgraphCount; s++) {
                if (types.get(s).isEmpty()) {
                    continue;  // a source schema without types would have an empty Query
                }
                List<String> lookups = new ArrayList<>();
                for (String type : types.get(s).keySet()) {
                    lookups.add(codeOnlyLookups.get(s).contains(type)
                        ? type.toLowerCase() + "ByCode(code: String!): " + type + " @lookup"
                        : type.toLowerCase() + "ById(id: ID!): " + type + " @lookup");
                }
                for (String name : abstractLookups.get(s)) {
                    lookups.add(name.toLowerCase() + "ById(id: ID!): " + name + " @lookup");
                }
                StringBuilder sdl = new StringBuilder("type Query {\n");
                rootFields.get(s).forEach(f -> sdl.append("  ").append(f).append('\n'));
                if (nestedLookups.contains(s)) {
                    // Lookups below an argument-less root field (a nested lookup)
                    sdl.append("  lookups").append(s).append(": Lookups").append(s).append("\n}\n");
                    sdl.append("type Lookups").append(s).append(" {\n");
                    lookups.forEach(l -> sdl.append("  ").append(l).append('\n'));
                } else {
                    lookups.forEach(l -> sdl.append("  ").append(l).append(" @internal\n"));
                }
                sdl.append("}\n");
                if (!mutationFields.get(s).isEmpty()) {
                    sdl.append("type Mutation {\n");
                    mutationFields.get(s).forEach(f -> sdl.append("  ").append(f).append('\n'));
                    sdl.append("}\n");
                }
                abstracts.get(s).forEach(definition -> sdl.append(definition).append('\n'));
                String interfaceName = "I" + s;
                if (interfaceFields.containsKey(interfaceName)) {
                    sdl.append("interface ").append(interfaceName).append(" {\n  id: ID!\n");
                    interfaceFields.get(interfaceName).forEach(f -> sdl.append("  ").append(f).append('\n'));
                    sdl.append("}\n");
                }
                int schema = s;
                types.get(s).forEach((type, fields) -> {
                    List<String> interfaces = implementations.get(schema).getOrDefault(type, List.of());
                    sdl.append("type ").append(type)
                        .append(interfaces.isEmpty() ? "" : " implements " + String.join(" & ", interfaces))
                        .append(" @key(fields: \"id\")")
                        .append(codeKeyed.contains(type) ? " @key(fields: \"code\")" : "").append(" {\n");
                    fields.forEach(f -> sdl.append("  ").append(f).append('\n'));
                    sdl.append("}\n");
                });
                inputs.add(Composer.SubgraphInput.of("s" + s, sdl.toString()));
            }
            return inputs;
        }

        String sdl() {
            StringBuilder text = new StringBuilder();
            for (Composer.SubgraphInput input : inputs()) {
                text.append("# ").append(input.name()).append('\n').append(input.sdl());
            }
            return text.toString();
        }

        /** A random query, or with mutation fields, sometimes a mutation with several of them. */
        String query() {
            StringBuilder body = new StringBuilder();
            boolean mutation = !mutationComposite.isEmpty() && random.nextInt(4) == 0;
            if (mutation) {
                int count = 2 + random.nextInt(2);
                for (int i = 0; i < count; i++) {
                    String field = mutationComposite.get(random.nextInt(mutationComposite.size()));
                    body.append("  m").append(i).append(": ").append(field, 0, field.indexOf(':'))
                        .append("(id: \"").append(i).append("\")");
                    selection(body, field.substring(field.indexOf(':') + 1), 1, "  ");
                }
            } else {
                int roots = 1 + random.nextInt(Math.min(2, rootComposite.size()));
                for (int i = 0; i < roots; i++) {
                    String root = rootComposite.get(random.nextInt(rootComposite.size()));
                    String name = root.substring(0, root.indexOf(':'));
                    String type = root.substring(root.indexOf(':') + 1).replace("[]", "");
                    body.append("  ").append(i > 0 ? "a" + i + ": " : "").append(name).append(directive());
                    if (type.isEmpty()) {
                        body.append('\n');
                    } else {
                        selection(body, type, 2, "  ");
                    }
                }
            }
            String header = (mutation ? "mutation" : "query")
                + (body.indexOf("$flag") >= 0 ? " ($flag: Boolean! = true)" : "");
            return header + " {\n" + body + "}\n";
        }

        /** With SKIP_INCLUDE, sometimes a @skip or @include directive (with a literal or the $flag variable). */
        private String directive() {
            if (!on(Feature.SKIP_INCLUDE) || random.nextInt(6) != 0) {
                return "";
            }
            String condition = switch (random.nextInt(3)) {
                case 0 -> "true";
                case 1 -> "false";
                default -> "$flag";
            };
            return random.nextBoolean() ? " @skip(if: " + condition + ")" : " @include(if: " + condition + ")";
        }

        private void selection(StringBuilder query, String type, int depth, String indent) {
            if (members.containsKey(type)) {
                // An interface or union: __typename, the interface's fields, and fragments on some members
                query.append(" {\n").append(indent).append("  __typename\n");
                if (type.startsWith("I")) {
                    query.append(indent).append("  id\n");
                    for (String field : interfaceFields.getOrDefault(type, List.of())) {
                        if (random.nextBoolean()) {
                            query.append(indent).append("  ").append(field, 0, field.indexOf('(')).append('\n');
                        }
                    }
                }
                for (String member : members.get(type)) {
                    if (random.nextInt(3) != 0) {
                        query.append(indent).append("  ... on ").append(member).append(directive());
                        selection(query, member, depth, indent + "  ");
                    }
                }
                query.append(indent).append("}\n");
                return;
            }
            query.append(" {\n");
            List<String> fields = new ArrayList<>(composite.get(type).keySet());
            java.util.Collections.shuffle(fields, random);
            int count = 1 + random.nextInt(Math.min(4, fields.size()));
            int alias = 0;
            for (String field : fields.subList(0, count)) {
                String fieldType = composite.get(type).get(field);
                if (!fieldType.isEmpty() && depth == 0) {
                    continue;
                }
                query.append(indent).append("  ");
                if (field.equals("code") && nullableCode.contains(type)) {
                    // A valid query selects one response key with one shape: code is String! on other types
                    query.append("nullableCode: ");
                } else if (random.nextInt(6) == 0) {
                    query.append("x").append(type).append('_').append(alias++).append(": ");
                }
                query.append(field).append(directive());
                if (fieldType.isEmpty()) {
                    query.append('\n');
                } else {
                    selection(query, fieldType.replace("[]", ""), depth - 1, indent + "  ");
                }
            }
            query.append(indent).append("  id\n").append(indent).append("}\n");
        }
    }
}
