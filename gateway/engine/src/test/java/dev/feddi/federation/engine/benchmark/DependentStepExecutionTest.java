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
import graphql.ExecutionResultImpl;
import graphql.GraphqlErrorBuilder;
import graphql.language.OperationDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Execution of dependent steps against the benchmark subgraphs: single (non-repeated)
 * dependent steps below a single object, and subgraph GraphQL errors in repeated steps.
 */
class DependentStepExecutionTest {

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
    void lookupBelowSingleObjectMatchesMonolith() {
        String query = "{ me { id name reviews { id body product { upc name inStock } } } }";

        var result = execute(query, UnaryOperator.identity());

        ExecutionResult expected = BenchmarkSubgraphs.monolith().execute(query);
        assertThat(result.getErrors()).isEmpty();
        assertThat((Object) result.getData()).isEqualTo(expected.getData());
    }

    @Test
    void subgraphErrorsInRepeatedStepAreReported() {
        String query = "{ users { id reviews { id product { upc name } } } }";

        var result = execute(query, client -> new ErrorAddingClient(client));

        assertThat(result.getErrors()).extracting(e -> e.getMessage()).containsExactly("partial failure");
        ExecutionResult expected = BenchmarkSubgraphs.monolith().execute(query);
        assertThat((Object) result.getData()).isEqualTo(expected.getData());
    }

    private static ExecutionResult execute(String query, UnaryOperator<SubgraphClient> productsClient) {
        ExecutionPlan plan = new OperationPlanner(schema.graph()).plan(Operation.parse(query, normalizer));
        Map<String, SubgraphClient> clients = new LinkedHashMap<>();
        for (String name : BenchmarkSubgraphs.NAMES) {
            SubgraphClient client = new SimulatedSubgraphClient(BenchmarkSubgraphs.subgraph(name), 0);
            clients.put(name, name.equals(BenchmarkSubgraphs.PRODUCTS) ? productsClient.apply(client) : client);
        }
        return new Executor(clients).execute(plan, Map.of()).block();
    }

    /**
     * Returns the real data plus a GraphQL error, like a subgraph reporting a partial failure.
     */
    private record ErrorAddingClient(SubgraphClient delegate) implements SubgraphClient {
        @Override
        public Mono<ExecutionResult> execute(OperationDefinition operation, Map<String, Object> variables) {
            return delegate.execute(operation, variables).map(result -> ExecutionResultImpl.newExecutionResult()
                .data(result.getData())
                .addError(GraphqlErrorBuilder.newError().message("partial failure").build())
                .build());
        }
    }
}
