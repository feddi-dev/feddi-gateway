package dev.feddi.federation.engine.benchmark;

import dev.feddi.federation.engine.executor.Executor;
import dev.feddi.federation.engine.executor.SubgraphClient;
import dev.feddi.federation.engine.planner.ExecutionPlan;
import dev.feddi.federation.engine.planner.OperationPlanner;
import dev.feddi.federation.engine.query.Operation;
import dev.feddi.federation.engine.query.OperationNormalizer;
import dev.feddi.federation.engine.testcase.SchemaDefinition;
import dev.feddi.federation.engine.testcase.TestCaseLoader;
import graphql.ExecutionResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the full graphql-gateway-benchmarks query ("heavy query", from k6/k6.js) through
 * the planner and executor against executable subgraphs with the benchmark's data, and
 * compares the result with a monolith serving the same data.
 *
 * <p>Also records how many subgraph calls the query needs. That number is the main
 * efficiency metric of the perf/query-execution work (see docs/perf-query-execution);
 * {@link #MAX_SUBGRAPH_CALLS} is lowered as optimizations land.
 */
class BenchmarkHeavyQueryTest {

    /** The exact query sent by the benchmark (k6/k6.js). */
    static final String HEAVY_QUERY = """
        fragment User on User {
          id
          username
          name
        }

        fragment Review on Review {
          id
          body
        }

        fragment Product on Product {
          inStock
          name
          price
          shippingEstimate
          upc
          weight
        }

        query TestQuery {
          users {
            ...User
            reviews {
              ...Review
              product {
                ...Product
                reviews {
                  ...Review
                  author {
                    ...User
                    reviews {
                      ...Review
                      product {
                        ...Product
                      }
                    }
                  }
                }
              }
            }
          }
          topProducts {
            ...Product
            reviews {
              ...Review
              author {
                ...User
                reviews {
                  ...Review
                  product {
                    ...Product
                  }
                }
              }
            }
          }
        }
        """;

    /**
     * Upper bound on subgraph calls for one heavy query. Baseline before optimizations: see
     * docs/perf-query-execution/06-progress-log.md. Lower this as call reduction lands.
     */
    static final int MAX_SUBGRAPH_CALLS = 26;

    private static SchemaDefinition schema;
    private static ExecutionPlan plan;

    @BeforeAll
    static void planHeavyQuery() throws Exception {
        schema = new TestCaseLoader().loadSchemaFromClasspath("schemas/benchmark_heavy_query/schema.yaml");
        var normalizer = OperationNormalizer.builder(schema.supergraphSchema())
            .inlineFragments(true).deduplicateFields(true).sortSelections(false)
            .processSkipInclude(true).build();
        plan = new OperationPlanner(schema.graph()).plan(Operation.parse(HEAVY_QUERY, normalizer));
    }

    @Test
    void heavyQueryMatchesMonolith() {
        var run = execute(0);

        assertThat(run.result().getErrors()).isEmpty();
        assertThat((Object) run.result().getData()).isEqualTo(expectedData());
    }

    @Test
    void heavyQueryIsCorrectWhenSubgraphsCompleteInRandomOrder() {
        Object expected = expectedData();
        for (int i = 0; i < 20; i++) {
            var run = execute(3);
            assertThat(run.result().getErrors()).as("errors in run %d", i).isEmpty();
            assertThat((Object) run.result().getData()).as("data in run %d", i).isEqualTo(expected);
        }
    }

    @Test
    void heavyQueryStaysWithinSubgraphCallBudget() {
        var run = execute(0);

        Map<String, Integer> callsPerSubgraph = new TreeMap<>();
        run.clients().forEach((name, client) -> callsPerSubgraph.put(name, client.calls().size()));
        int total = callsPerSubgraph.values().stream().mapToInt(Integer::intValue).sum();
        System.out.printf("Heavy query: %d plan steps, %d subgraph calls %s%n",
            plan.steps().size(), total, callsPerSubgraph);

        assertThat(total).isLessThanOrEqualTo(MAX_SUBGRAPH_CALLS);
    }

    private static Object expectedData() {
        ExecutionResult monolith = BenchmarkSubgraphs.monolith().execute(HEAVY_QUERY);
        assertThat(monolith.getErrors()).isEmpty();
        return monolith.getData();
    }

    private record Run(ExecutionResult result, Map<String, SimulatedSubgraphClient> clients) {
    }

    private static Run execute(int maxLatencyMillis) {
        Map<String, SimulatedSubgraphClient> simulated = new LinkedHashMap<>();
        Map<String, SubgraphClient> clients = new LinkedHashMap<>();
        for (String name : BenchmarkSubgraphs.NAMES) {
            var client = new SimulatedSubgraphClient(BenchmarkSubgraphs.subgraph(name), maxLatencyMillis);
            simulated.put(name, client);
            clients.put(name, client);
        }
        ExecutionResult result = new Executor(clients).execute(plan, Map.of()).block();
        return new Run(result, simulated);
    }
}
