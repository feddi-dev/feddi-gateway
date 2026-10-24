package dev.feddi.federation.engine.benchmark;

import dev.feddi.federation.engine.executor.BatchingOptions;
import dev.feddi.federation.engine.executor.Executor;
import dev.feddi.federation.engine.executor.SubgraphClient;
import dev.feddi.federation.engine.planner.OperationPlanner;
import dev.feddi.federation.engine.query.Operation;
import dev.feddi.federation.engine.query.OperationNormalizer;
import dev.feddi.federation.engine.testcase.SchemaDefinition;
import dev.feddi.federation.engine.testcase.TestCaseLoader;
import graphql.ExecutionResult;
import graphql.language.OperationDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Lookups that take their entities from the objects a root field fetched from the same subgraph. */
class ResultReuseTest {

    // The reviews' products are all among the top five products
    private static final String QUERY = """
        {
          topProducts(first: 5) { upc name price }
          users { reviews { product { upc name price } } }
        }
        """;

    private static SchemaDefinition schema;
    private static OperationNormalizer normalizer;

    @BeforeAll
    static void loadSchema() throws Exception {
        schema = new TestCaseLoader().loadSchemaFromClasspath("schemas/benchmark_heavy_query/schema.yaml");
        normalizer = OperationNormalizer.builder(schema.supergraphSchema())
            .inlineFragments(true).deduplicateFields(true).sortSelections(false).build();
    }

    @Test
    void lookupTakesItsEntitiesFromTheRootFieldsObjects() {
        var run = execute(QUERY, true);

        assertMatchesMonolith(QUERY, run.result());
        assertThat(run.clients().get(BenchmarkSubgraphs.PRODUCTS).calls()).as("topProducts only").hasSize(1);
    }

    @Test
    void subgraphWithoutResultReuseIsLookedUp() {
        var run = execute(QUERY, false);

        assertMatchesMonolith(QUERY, run.result());
        assertThat(run.clients().get(BenchmarkSubgraphs.PRODUCTS).calls()).as("topProducts and the lookup").hasSize(2);
    }

    @Test
    void lookupSelectingMoreThanTheRootFieldIsLookedUp() {
        // topProducts has no weight: the lookup cannot be answered from it
        String query = """
            {
              topProducts(first: 5) { upc name }
              users { reviews { product { upc name weight } } }
            }
            """;

        var run = execute(query, true);

        assertMatchesMonolith(query, run.result());
        assertThat(run.clients().get(BenchmarkSubgraphs.PRODUCTS).calls()).hasSize(2);
    }

    private record Run(ExecutionResult result, Map<String, SimulatedSubgraphClient> clients) {
    }

    private static Run execute(String query, boolean reusesResults) {
        var plan = new OperationPlanner(schema.graph()).plan(Operation.parse(query, normalizer));
        Map<String, SimulatedSubgraphClient> simulated = new LinkedHashMap<>();
        Map<String, SubgraphClient> clients = new LinkedHashMap<>();
        for (String name : BenchmarkSubgraphs.NAMES) {
            var client = new SimulatedSubgraphClient(BenchmarkSubgraphs.subgraph(name), 0, BatchingOptions.NONE);
            simulated.put(name, client);
            clients.put(name, reusesResults ? client : withoutResultReuse(client));
        }
        return new Run(new Executor(clients).execute(plan, Map.of()).block(), simulated);
    }

    private static SubgraphClient withoutResultReuse(SubgraphClient client) {
        return new SubgraphClient() {
            @Override
            public Mono<ExecutionResult> execute(OperationDefinition operation, Map<String, Object> variables) {
                return client.execute(operation, variables);
            }

            @Override
            public Mono<List<ExecutionResult>> executeBatch(OperationDefinition operation,
                                                            List<Map<String, Object>> variableSets) {
                return client.executeBatch(operation, variableSets);
            }

            @Override
            public boolean reusesResults() {
                return false;
            }
        };
    }

    private static void assertMatchesMonolith(String query, ExecutionResult result) {
        ExecutionResult expected = BenchmarkSubgraphs.monolith().execute(query);
        assertThat(expected.getErrors()).isEmpty();
        assertThat(result.getErrors()).isEmpty();
        assertThat((Object) result.getData()).isEqualTo(expected.getData());
    }
}
