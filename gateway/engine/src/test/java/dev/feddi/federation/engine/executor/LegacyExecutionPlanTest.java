package dev.feddi.federation.engine.executor;

import dev.feddi.federation.engine.planner.ExecutionPlan;
import dev.feddi.federation.engine.planner.ExecutionStep;
import dev.feddi.federation.engine.planner.OperationPlanner;
import dev.feddi.federation.engine.query.Operation;
import dev.feddi.federation.engine.query.OperationNormalizer;
import dev.feddi.federation.engine.testcase.TestCaseLoader;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the supported constructor for plans without explicit entity paths. */
class LegacyExecutionPlanTest {
    @ParameterizedTest
    @ValueSource(strings = {
        "products_reviews/basic_lookup",
        "products_reviews/04_key_fields_not_leaked",
        "benchmark_heavy_query/01_nested_product_entity_fields",
        "benchmark_heavy_query/03_nested_product_lookup_null",
        "nested_entity_context_selection/01_type_qualified_nested_context"
    })
    void executesPlansWithoutEntityPathMetadata(String scenario) throws Exception {
        var loader = new TestCaseLoader();
        String[] parts = scenario.split("/");
        var schema = loader.loadSchemaFromClasspath("schemas/" + parts[0] + "/schema.yaml");
        try (var input = getClass().getResourceAsStream(
                "/schemas/" + parts[0] + "/executions/" + parts[1] + ".yaml")) {
            var fixture = loader.loadExecutionTest(input);
            var normalizer = OperationNormalizer.builder(schema.supergraphSchema())
                .inlineFragments(true).deduplicateFields(true).sortSelections(false)
                .processSkipInclude(true).build();
            var planned = new OperationPlanner(schema.graph()).plan(Operation.parse(fixture.query(), normalizer));
            var legacy = ExecutionPlan.of(planned.steps().stream().map(step -> new ExecutionStep(
                step.id(), step.subgraph(), step.operation(), step.dependsOn(), step.parallelWith(),
                step.requirements(), step.repeatedExecution(), step.artificialFieldPaths(),
                step.requestedFieldPaths())).toList());
            assertThat(legacy.steps()).allSatisfy(step -> assertThat(step.entityPath()).isNull());
            Map<String, SubgraphClient> clients = new LinkedHashMap<>();
            for (String subgraph : planned.steps().stream().map(ExecutionStep::subgraph).distinct().toList()) {
                clients.put(subgraph, new ExecutingMockSubgraphClient(subgraph,
                    schema.getSubgraphSchema(subgraph), fixture.subgraphCalls().stream()
                        .filter(call -> call.subgraph().equals(subgraph)).toList()));
            }
            var result = new Executor(clients).execute(legacy, fixture.variables()).block();
            assertThat(result.getErrors()).isEmpty();
            assertThat((Object) result.getData()).isEqualTo(fixture.expectedResponse().get("data"));
        }
    }
}
