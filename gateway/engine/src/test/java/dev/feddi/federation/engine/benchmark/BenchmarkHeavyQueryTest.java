package dev.feddi.federation.engine.benchmark;

import dev.feddi.federation.engine.executor.BatchingOptions;
import dev.feddi.federation.engine.executor.Executor;
import dev.feddi.federation.engine.executor.SubgraphClient;
import dev.feddi.federation.engine.planner.ExecutionPlan;
import dev.feddi.federation.engine.planner.OperationPlanner;
import dev.feddi.federation.engine.query.Operation;
import dev.feddi.federation.engine.query.OperationNormalizer;
import dev.feddi.federation.engine.testcase.SchemaDefinition;
import dev.feddi.federation.engine.testcase.TestCaseLoader;
import graphql.ExecutionResult;
import graphql.language.OperationDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
     * Upper bound on subgraph calls for one heavy query, per batching mode. Baseline before
     * optimizations was 337: see docs/perf-query-execution/06-progress-log.md. Lower these as
     * call reduction lands.
     */
    static final Map<BatchingOptions.Mode, Integer> MAX_SUBGRAPH_CALLS = Map.of(
        BatchingOptions.Mode.NONE, 20,
        BatchingOptions.Mode.ALIAS, 8,
        BatchingOptions.Mode.VARIABLES, 8);

    private static SchemaDefinition schema;
    private static ExecutionPlan plan;

    @BeforeAll
    static void planHeavyQuery() throws Exception {
        schema = new TestCaseLoader().loadSchemaFromClasspath("schemas/benchmark_heavy_query/schema.yaml");
        var normalizer = OperationNormalizer.builder(schema.supergraphSchema())
            .inlineFragments(true).deduplicateFields(true).sortSelections(false).build();
        plan = new OperationPlanner(schema.graph()).plan(Operation.parse(HEAVY_QUERY, normalizer));
    }

    @ParameterizedTest
    @EnumSource(BatchingOptions.Mode.class)
    void heavyQueryMatchesMonolith(BatchingOptions.Mode mode) {
        var run = execute(mode, 0);

        assertThat(run.result().getErrors()).isEmpty();
        assertThat((Object) run.result().getData()).isEqualTo(expectedData());
    }

    @ParameterizedTest
    @EnumSource(BatchingOptions.Mode.class)
    void heavyQueryIsCorrectWhenSubgraphsCompleteInRandomOrder(BatchingOptions.Mode mode) {
        Object expected = expectedData();
        for (int i = 0; i < 20; i++) {
            var run = execute(mode, 3);
            assertThat(run.result().getErrors()).as("errors in run %d", i).isEmpty();
            assertThat((Object) run.result().getData()).as("data in run %d", i).isEqualTo(expected);
        }
    }

    @ParameterizedTest
    @EnumSource(BatchingOptions.Mode.class)
    void heavyQueryStaysWithinSubgraphCallBudget(BatchingOptions.Mode mode) {
        var run = execute(mode, 0);

        Map<String, Integer> callsPerSubgraph = new TreeMap<>();
        run.clients().forEach((name, client) -> callsPerSubgraph.put(name, client.calls().size()));
        int total = callsPerSubgraph.values().stream().mapToInt(Integer::intValue).sum();
        System.out.printf("Heavy query (%s): %d plan steps, %d subgraph calls %s%n",
            mode, plan.steps().size(), total, callsPerSubgraph);

        assertThat(total).isLessThanOrEqualTo(MAX_SUBGRAPH_CALLS.get(mode));
    }

    @ParameterizedTest
    @EnumSource(BatchingOptions.Mode.class)
    void noEntityIsFetchedTwiceWithTheSameOperation(BatchingOptions.Mode mode) {
        var run = execute(mode, 3);

        // Every lookup's variable set, per subgraph and operation, across all calls of the request
        Map<String, List<Object>> fetched = new TreeMap<>();
        run.clients().forEach((name, client) -> client.calls().forEach(call -> {
            List<Object> variableSets = call.variables().get("variableSets") instanceof List<?> sets
                ? List.copyOf(sets) : List.of(call.variables());
            fetched.computeIfAbsent(name + " " + call.operation(), k -> new ArrayList<>()).addAll(variableSets);
        }));
        fetched.forEach((operation, variableSets) ->
            assertThat(variableSets).as(operation).doesNotHaveDuplicates());
    }

    @ParameterizedTest
    @EnumSource(BatchingOptions.Mode.class)
    void failedLookupsGiveErrorsAlsoWhereTheirEntitiesAreReused(BatchingOptions.Mode mode) {
        var batching = new BatchingOptions(mode, BatchingOptions.DEFAULT_MAX_BATCH_SIZE);
        for (String failing : BenchmarkSubgraphs.NAMES) {
            Map<String, SubgraphClient> clients = new LinkedHashMap<>();
            for (String name : BenchmarkSubgraphs.NAMES) {
                var simulated = new SimulatedSubgraphClient(BenchmarkSubgraphs.subgraph(name), 0, batching);
                // Root fields have no variables; the lookups of one subgraph fail
                clients.put(name, !name.equals(failing) ? simulated : new SubgraphClient() {
                    @Override
                    public Mono<ExecutionResult> execute(OperationDefinition operation, Map<String, Object> variables) {
                        return variables.isEmpty() ? simulated.execute(operation, variables)
                            : Mono.error(new IllegalStateException("lookup failed"));
                    }

                    @Override
                    public Mono<List<ExecutionResult>> executeBatch(OperationDefinition operation,
                                                                    List<Map<String, Object>> variableSets) {
                        return Mono.error(new IllegalStateException("lookup failed"));
                    }
                });
            }

            ExecutionResult result = new Executor(clients).execute(plan, Map.of()).block();

            assertThat(result.getErrors()).as(failing)
                .allSatisfy(error -> assertThat(error.getMessage()).contains("lookup failed"));
        }
    }

    private static Object expectedData() {
        ExecutionResult monolith = BenchmarkSubgraphs.monolith().execute(HEAVY_QUERY);
        assertThat(monolith.getErrors()).isEmpty();
        return monolith.getData();
    }

    private record Run(ExecutionResult result, Map<String, SimulatedSubgraphClient> clients) {
    }

    private static Run execute(BatchingOptions.Mode mode, int maxLatencyMillis) {
        var batching = new BatchingOptions(mode, BatchingOptions.DEFAULT_MAX_BATCH_SIZE);
        Map<String, SimulatedSubgraphClient> simulated = new LinkedHashMap<>();
        Map<String, SubgraphClient> clients = new LinkedHashMap<>();
        for (String name : BenchmarkSubgraphs.NAMES) {
            var client = new SimulatedSubgraphClient(BenchmarkSubgraphs.subgraph(name), maxLatencyMillis, batching);
            simulated.put(name, client);
            clients.put(name, client);
        }
        ExecutionResult result = new Executor(clients).execute(plan, Map.of()).block();
        return new Run(result, simulated);
    }
}
