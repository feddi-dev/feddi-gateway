package dev.feddi.federation.engine.benchmark;

import dev.feddi.federation.engine.executor.BatchingOptions;
import dev.feddi.federation.engine.executor.BatchingOptions.Mode;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Batched entity lookups (alias and variable batching) in repeated steps.
 */
class BatchingExecutionTest {

    // 6 users -> 6 unique reviews lookups (one per user id)
    private static final String USERS_REVIEWS = "{ users { id reviews { id body } } }";

    private static SchemaDefinition schema;
    private static OperationNormalizer normalizer;

    @BeforeAll
    static void loadSchema() throws Exception {
        schema = new TestCaseLoader().loadSchemaFromClasspath("schemas/benchmark_heavy_query/schema.yaml");
        normalizer = OperationNormalizer.builder(schema.supergraphSchema())
            .inlineFragments(true).deduplicateFields(true).sortSelections(false)
            .processSkipInclude(true).build();
    }

    @ParameterizedTest
    @EnumSource(value = Mode.class, names = {"ALIAS", "VARIABLES"})
    void batchesAreSplitAtMaxBatchSize(Mode mode) {
        var run = execute(USERS_REVIEWS, new BatchingOptions(mode, 4), UnaryOperator.identity());

        assertMatchesMonolith(USERS_REVIEWS, run.result());
        // 6 unique users with at most 4 per request -> 2 requests
        assertThat(run.clients().get(BenchmarkSubgraphs.REVIEWS).calls()).hasSize(2);
    }

    @ParameterizedTest
    @EnumSource(value = Mode.class, names = {"ALIAS", "VARIABLES"})
    void failedBatchIsReportedOnceAndNullsEveryEntity(Mode mode) {
        var run = execute(USERS_REVIEWS, new BatchingOptions(mode, 64), client -> new FailingClient(client.batching()));

        assertThat(run.result().getErrors()).hasSize(1);
        List<Map<String, Object>> users = list(((Map<String, Object>) run.result().getData()).get("users"));
        assertThat(users).hasSize(6).allSatisfy(user -> assertThat(user).containsEntry("reviews", null));
    }

    @Test
    void variableBatchWithWrongResultCountIsAnError() {
        var run = execute(USERS_REVIEWS, new BatchingOptions(Mode.VARIABLES, 64),
            client -> new TruncatingClient(client));

        assertThat(run.result().getErrors()).singleElement()
            .satisfies(e -> assertThat(e.getMessage()).contains("returned 5 results for a batch of 6"));
    }

    @Test
    void singleEntityIsNotBatched() {
        String query = "{ topProducts(first: 1) { upc reviews { id } } }";

        var run = execute(query, new BatchingOptions(Mode.ALIAS, 64), UnaryOperator.identity());

        assertMatchesMonolith(query, run.result());
        assertThat(run.clients().get(BenchmarkSubgraphs.REVIEWS).calls()).singleElement()
            .satisfies(call -> assertThat(call.operation()).doesNotContain("_b0_"));
    }

    private static void assertMatchesMonolith(String query, ExecutionResult result) {
        ExecutionResult expected = BenchmarkSubgraphs.monolith().execute(query);
        assertThat(result.getErrors()).isEmpty();
        assertThat((Object) result.getData()).isEqualTo(expected.getData());
    }

    private record Run(ExecutionResult result, Map<String, SimulatedSubgraphClient> clients) {
    }

    private static Run execute(String query, BatchingOptions batching, UnaryOperator<SubgraphClient> reviewsClient) {
        var plan = new OperationPlanner(schema.graph()).plan(Operation.parse(query, normalizer));
        Map<String, SimulatedSubgraphClient> simulated = new LinkedHashMap<>();
        Map<String, SubgraphClient> clients = new LinkedHashMap<>();
        for (String name : BenchmarkSubgraphs.NAMES) {
            var client = new SimulatedSubgraphClient(BenchmarkSubgraphs.subgraph(name), 0, batching);
            simulated.put(name, client);
            clients.put(name, name.equals(BenchmarkSubgraphs.REVIEWS) ? reviewsClient.apply(client) : client);
        }
        return new Run(new Executor(clients).execute(plan, Map.of()).block(), simulated);
    }

    private record FailingClient(BatchingOptions batching) implements SubgraphClient {
        @Override
        public Mono<ExecutionResult> execute(OperationDefinition operation, Map<String, Object> variables) {
            return Mono.error(new IllegalStateException("reviews unavailable"));
        }

        @Override
        public Mono<List<ExecutionResult>> executeBatch(OperationDefinition operation,
                                                        List<Map<String, Object>> variableSets) {
            return Mono.error(new IllegalStateException("reviews unavailable"));
        }
    }

    /** A broken variable-batching subgraph that drops the last result. */
    private record TruncatingClient(SubgraphClient delegate) implements SubgraphClient {
        @Override
        public Mono<ExecutionResult> execute(OperationDefinition operation, Map<String, Object> variables) {
            return delegate.execute(operation, variables);
        }

        @Override
        public Mono<List<ExecutionResult>> executeBatch(OperationDefinition operation,
                                                        List<Map<String, Object>> variableSets) {
            return delegate.executeBatch(operation, variableSets).map(r -> r.subList(0, r.size() - 1));
        }

        @Override
        public BatchingOptions batching() {
            return delegate.batching();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
