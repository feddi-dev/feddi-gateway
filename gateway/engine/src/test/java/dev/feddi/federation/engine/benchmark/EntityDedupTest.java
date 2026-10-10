package dev.feddi.federation.engine.benchmark;

import dev.feddi.federation.engine.executor.Executor;
import dev.feddi.federation.engine.executor.SubgraphClient;
import dev.feddi.federation.engine.planner.OperationPlanner;
import dev.feddi.federation.engine.query.Operation;
import dev.feddi.federation.engine.query.OperationNormalizer;
import dev.feddi.federation.engine.testcase.SchemaDefinition;
import dev.feddi.federation.engine.testcase.TestCaseLoader;
import graphql.ExecutionResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Entity deduplication in repeated (per-entity) steps: each unique entity is fetched once,
 * and the result is distributed to every position where the entity appears.
 */
class EntityDedupTest {

    private static SchemaDefinition schema;
    private static OperationNormalizer normalizer;

    @BeforeAll
    static void loadSchema() throws Exception {
        schema = new TestCaseLoader().loadSchemaFromClasspath("schemas/benchmark_heavy_query/schema.yaml");
        normalizer = OperationNormalizer.builder(schema.supergraphSchema())
            .inlineFragments(true).deduplicateFields(true).sortSelections(false)
            .processSkipInclude(true).build();
    }

    @Test
    void sameEntityAtManyPositionsIsFetchedOnce() {
        // 6 users x reviews 1 and 2, all of product 1: 12 positions, one product.
        String query = "{ users { id reviews { id product { upc name } } } }";

        var run = execute(query, UnaryOperator.identity());

        assertMatchesMonolith(query, run.result());
        assertThat(run.clients().get(BenchmarkSubgraphs.PRODUCTS).calls()).hasSize(1);
    }

    @Test
    void sameEntityUnderDifferentPathsKeepsItsOwnSelection() {
        // Product 1 appears under both roots; each path selects different fields.
        // A shared result map would leak fields from one path into the other.
        String query = """
            {
              users { reviews { product { upc name } } }
              topProducts(first: 1) { upc reviews { product { upc price weight } } }
            }
            """;

        var run = execute(query, UnaryOperator.identity());

        assertMatchesMonolith(query, run.result());
    }

    @Test
    void sharedCallDoesNotLeakFieldsBetweenResponsePaths() {
        // Both aliases need the identical lookup product(upc: "1") { reviews { author { id } } }, so
        // the call is shared within the request. Only "b" asks for the author's name, which a
        // later accounts step merges into the author objects: with shared (uncopied) result
        // maps, "name" would leak into "a".
        String query = """
            {
              a: topProducts(first: 1) { upc reviews { id author { id } } }
              b: topProducts(first: 1) { upc reviews { id author { id name } } }
            }
            """;

        var run = execute(query, UnaryOperator.identity());

        assertMatchesMonolith(query, run.result());
        assertThat(run.clients().get(BenchmarkSubgraphs.REVIEWS).calls()).hasSize(1);
    }

    @Test
    void failedCallIsReportedOnceAndNullsEveryPosition() {
        String query = "{ users { id reviews { id product { upc name } } } }";

        var run = execute(query, client -> new FailingClient());

        assertThat(run.result().getErrors()).hasSize(1);
        Map<String, Object> data = run.result().getData();
        List<Map<String, Object>> users = list(data.get("users"));
        assertThat(users).hasSize(6);
        for (Map<String, Object> user : users) {
            for (Map<String, Object> review : list(user.get("reviews"))) {
                Map<String, Object> product = map(review.get("product"));
                assertThat(product).containsEntry("upc", "1").containsEntry("name", null);
            }
        }
    }

    private static void assertMatchesMonolith(String query, ExecutionResult result) {
        ExecutionResult expected = BenchmarkSubgraphs.monolith().execute(query);
        assertThat(expected.getErrors()).isEmpty();
        assertThat(result.getErrors()).isEmpty();
        assertThat((Object) result.getData()).isEqualTo(expected.getData());
    }

    private record Run(ExecutionResult result, Map<String, SimulatedSubgraphClient> clients) {
    }

    /**
     * Executes with simulated subgraphs; {@code productsClient} may replace the products client.
     */
    private static Run execute(String query, UnaryOperator<SubgraphClient> productsClient) {
        var plan = new OperationPlanner(schema.graph()).plan(Operation.parse(query, normalizer));
        Map<String, SimulatedSubgraphClient> simulated = new LinkedHashMap<>();
        Map<String, SubgraphClient> clients = new LinkedHashMap<>();
        for (String name : BenchmarkSubgraphs.NAMES) {
            var client = new SimulatedSubgraphClient(BenchmarkSubgraphs.subgraph(name), 0);
            simulated.put(name, client);
            clients.put(name, name.equals(BenchmarkSubgraphs.PRODUCTS) ? productsClient.apply(client) : client);
        }
        return new Run(new Executor(clients).execute(plan, Map.of()).block(), simulated);
    }

    private static final class FailingClient implements SubgraphClient {
        @Override
        public Mono<ExecutionResult> execute(graphql.language.OperationDefinition operation,
                                             Map<String, Object> variables) {
            return Mono.error(new IllegalStateException("products unavailable"));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
