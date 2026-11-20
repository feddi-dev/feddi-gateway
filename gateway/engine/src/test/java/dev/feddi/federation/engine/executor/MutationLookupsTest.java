package dev.feddi.federation.engine.executor;

import dev.feddi.federation.engine.planner.OperationPlanner;
import dev.feddi.federation.engine.query.Operation;
import dev.feddi.federation.engine.query.OperationNormalizer;
import dev.feddi.federation.engine.testcase.TestCaseLoader;
import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.language.OperationDefinition;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * In a mutation, the lookups after a top-level field see what it changed: a lookup is not reused from one that ran
 * before the field, although it sends the same operation with the same variables, nor merged with it (alias mode).
 * (The fixture mocks answer the same call always alike, so this test counts the calls itself.)
 */
class MutationLookupsTest {

    @ParameterizedTest
    @EnumSource(value = BatchingOptions.Mode.class, names = {"NONE", "ALIAS"})
    void lookupsAfterEachMutationFieldAreSent(BatchingOptions.Mode mode) throws Exception {
        var loader = new TestCaseLoader();
        var schema = loader.loadSchemaFromClasspath("schemas/mutations/schema.yaml");
        var normalizer = OperationNormalizer.builder(schema.supergraphSchema())
            .inlineFragments(true).deduplicateFields(true).sortSelections(false).build();
        // Another subgraph's field between the two addProduct fields: the second runs after the first's lookup
        var plan = new OperationPlanner(schema.graph()).plan(Operation.parse("""
            mutation {
              first: addProduct(input: { name: "Widget", price: 1.0 }) { isAvailable }
              sum: add(num: 1, requestId: "r1")
              second: addProduct(input: { name: "Widget", price: 1.0 }) { isAvailable }
            }
            """, normalizer));

        AtomicInteger lookups = new AtomicInteger();
        Map<String, SubgraphClient> clients = Map.of(
            "a", client(mode, operation -> Map.of(
                OperationTexts.compact(operation).contains("first:") ? "first" : "second", Map.of("id", "p1"))),
            "b", client(mode, operation -> Map.of("productById",
                Map.of("isAvailable", lookups.incrementAndGet() == 1))),
            "c", client(mode, operation -> Map.of("sum", 1)));

        ExecutionResult result = new Executor(clients).execute(plan, Map.of()).block();

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.<Map<String, Object>>getData()).isEqualTo(Map.of(
            "first", Map.of("isAvailable", true), "sum", 1, "second", Map.of("isAvailable", false)));
        assertThat(lookups).hasValue(2);
    }

    private static SubgraphClient client(BatchingOptions.Mode mode,
                                         Function<OperationDefinition, Map<String, Object>> data) {
        return new SubgraphClient() {
            @Override
            public BatchingOptions batching() {
                return new BatchingOptions(mode, 64);
            }

            @Override
            public Mono<ExecutionResult> execute(OperationDefinition operation, Map<String, Object> variables) {
                return Mono.fromSupplier(() -> ExecutionResultImpl.newExecutionResult()
                    .data(ResultCopies.deepCopy(data.apply(operation))).build());
            }

            @Override
            public Mono<List<ExecutionResult>> executeBatch(OperationDefinition operation,
                                                            List<Map<String, Object>> variableSets) {
                return Mono.error(new IllegalStateException("no batches expected"));
            }
        };
    }
}
