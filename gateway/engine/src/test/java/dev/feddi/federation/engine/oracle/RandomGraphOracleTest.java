package dev.feddi.federation.engine.oracle;

import dev.feddi.federation.engine.compose.Composer;
import dev.feddi.federation.engine.compose.CompositionResult;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The planner on graphs nobody wrote by hand: random composite schemas (entities spread over source schemas, shared
 * and owned fields, relations, root fields in several schemas, requirements) with random queries, each checked
 * against the monolith like {@link PlanExecutionOracleTest}. A failure names the seed; the schemas and query are in
 * the message to reproduce it.
 */
class RandomGraphOracleTest {

    private static final int SEEDS = Integer.getInteger("randomGraph.seeds", 150);
    private static final int QUERIES_PER_GRAPH = 4;

    @TestFactory
    Stream<DynamicTest> randomGraphsReturnTheMonolithsData() {
        return IntStream.range(0, SEEDS).mapToObj(seed -> DynamicTest.dynamicTest("seed " + seed, () -> {
            RandomGraph graph = new RandomGraph(new Random(seed));
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

    /** A random set of source schemas and queries against their composite schema. */
    private static final class RandomGraph {
        private final Random random;
        private final int subgraphCount;
        private final int typeCount;
        // per subgraph: type -> field definitions (SDL lines)
        private final List<Map<String, List<String>>> types = new ArrayList<>();
        private final List<List<String>> rootFields = new ArrayList<>();
        // composite view for queries: type -> field -> type name ("" for scalars), list flag in name suffix "[]"
        private final Map<String, Map<String, String>> composite = new LinkedHashMap<>();
        private final List<String> rootComposite = new ArrayList<>();
        // per subgraph: abstract type definitions (SDL) and which entity types implement which interface there
        private final List<List<String>> abstracts = new ArrayList<>();
        private final List<Map<String, List<String>>> implementations = new ArrayList<>();
        // composite: abstract type -> possible entity types (from the one subgraph that defines it)
        private final Map<String, List<String>> members = new LinkedHashMap<>();

        RandomGraph(Random random) {
            this.random = random;
            this.subgraphCount = 2 + random.nextInt(3);
            this.typeCount = 2 + random.nextInt(3);
            for (int s = 0; s < subgraphCount; s++) {
                types.add(new LinkedHashMap<>());
                rootFields.add(new ArrayList<>());
                abstracts.add(new ArrayList<>());
                implementations.add(new LinkedHashMap<>());
            }
            build();
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
            // Requirements: a field in one definer that needs a scalar another definer owns
            for (int t = 0; t < typeCount; t++) {
                String type = "E" + t;
                List<Integer> definers = definers(type);
                if (definers.size() < 2 || random.nextInt(3) != 0) {
                    continue;
                }
                int requiring = s(definers);
                for (String line : new ArrayList<>(types.get(definers.get((definers.indexOf(requiring) + 1)
                        % definers.size())).get(type))) {
                    if (line.startsWith("f") && !line.contains("@shareable")) {
                        String required = line.substring(0, line.indexOf(':'));
                        String argumentType = line.substring(line.indexOf(':') + 2);
                        String field = "req" + t + "_" + required;
                        types.get(requiring).get(type).add(field + "(v: " + argumentType + " @require(field: \""
                            + required + "\")): String");
                        composite.get(type).put(field, "");
                        break;
                    }
                }
            }
            // Interfaces and unions over a schema's entity types, with root fields returning lists of them
            for (int s = 0; s < subgraphCount; s++) {
                List<String> defined = new ArrayList<>(types.get(s).keySet());
                if (defined.size() < 2) {
                    continue;
                }
                if (random.nextInt(3) == 0) {
                    String name = "I" + s;
                    List<String> implementing = pickTypes(defined, 2 + random.nextInt(defined.size() - 1));
                    abstracts.get(s).add("interface " + name + " { id: ID! }");
                    for (String t : implementing) {
                        implementations.get(s).computeIfAbsent(t, k -> new ArrayList<>()).add(name);
                    }
                    members.put(name, implementing);
                    rootFields.get(s).add("iq" + s + ": [" + name + "]");
                    rootComposite.add("iq" + s + ":" + name + "[]");
                }
                if (random.nextInt(3) == 0) {
                    String name = "U" + s;
                    List<String> unionMembers = pickTypes(defined, 2 + random.nextInt(defined.size() - 1));
                    abstracts.get(s).add("union " + name + " = " + String.join(" | ", unionMembers));
                    members.put(name, unionMembers);
                    rootFields.get(s).add("uq" + s + ": [" + name + "]");
                    rootComposite.add("uq" + s + ":" + name + "[]");
                }
            }
            // Root fields: at least one per schema that defines types; shared roots with @shareable
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
                StringBuilder sdl = new StringBuilder("type Query {\n");
                rootFields.get(s).forEach(f -> sdl.append("  ").append(f).append('\n'));
                for (String type : types.get(s).keySet()) {
                    sdl.append("  ").append(lookupName(type)).append("(id: ID!): ").append(type)
                        .append(" @lookup @internal\n");
                }
                sdl.append("}\n");
                abstracts.get(s).forEach(definition -> sdl.append(definition).append('\n'));
                int schema = s;
                types.get(s).forEach((type, fields) -> {
                    List<String> interfaces = implementations.get(schema).getOrDefault(type, List.of());
                    sdl.append("type ").append(type)
                        .append(interfaces.isEmpty() ? "" : " implements " + String.join(" & ", interfaces))
                        .append(" @key(fields: \"id\") {\n");
                    fields.forEach(f -> sdl.append("  ").append(f).append('\n'));
                    sdl.append("}\n");
                });
                inputs.add(Composer.SubgraphInput.of("s" + s, sdl.toString()));
            }
            return inputs;
        }

        private static String lookupName(String type) {
            return type.toLowerCase() + "ById";
        }

        String sdl() {
            StringBuilder text = new StringBuilder();
            for (Composer.SubgraphInput input : inputs()) {
                text.append("# ").append(input.name()).append('\n').append(input.sdl());
            }
            return text.toString();
        }

        String query() {
            StringBuilder query = new StringBuilder("{\n");
            int roots = 1 + random.nextInt(Math.min(2, rootComposite.size()));
            for (int i = 0; i < roots; i++) {
                String root = rootComposite.get(random.nextInt(rootComposite.size()));
                String name = root.substring(0, root.indexOf(':'));
                String type = root.substring(root.indexOf(':') + 1).replace("[]", "");
                query.append("  ").append(i > 0 ? "a" + i + ": " : "").append(name);
                selection(query, type, 2, "  ");
            }
            return query.append("}\n").toString();
        }

        private void selection(StringBuilder query, String type, int depth, String indent) {
            if (members.containsKey(type)) {
                // An interface or union: __typename, the interface's id, and fragments on some members
                query.append(" {\n").append(indent).append("  __typename\n");
                if (type.startsWith("I")) {
                    query.append(indent).append("  id\n");
                }
                for (String member : members.get(type)) {
                    if (random.nextInt(3) != 0) {
                        query.append(indent).append("  ... on ").append(member);
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
                if (random.nextInt(6) == 0) {
                    query.append("x").append(type).append('_').append(alias++).append(": ");
                }
                query.append(field);
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
