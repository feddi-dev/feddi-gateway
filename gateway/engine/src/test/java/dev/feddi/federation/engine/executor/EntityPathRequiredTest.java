package dev.feddi.federation.engine.executor;

import dev.feddi.federation.engine.planner.ExecutionPlan;
import dev.feddi.federation.engine.planner.ExecutionStep;
import dev.feddi.federation.engine.planner.OperationPlanner;
import dev.feddi.federation.engine.query.Operation;
import dev.feddi.federation.engine.query.OperationNormalizer;
import dev.feddi.federation.engine.testcase.TestCaseLoader;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Repeated steps find their targets only through their entity path. A plan whose repeated
 * steps lack it (e.g. assembled by hand) fails instead of falling back to searching the
 * response for objects with matching key fields, which targets unrelated objects (#49).
 */
class EntityPathRequiredTest {

    @Test
    void repeatedStepWithoutEntityPathIsAnError() throws Exception {
        var loader = new TestCaseLoader();
        var schema = loader.loadSchemaFromClasspath("schemas/products_reviews/schema.yaml");
        try (var input = getClass().getResourceAsStream("/schemas/products_reviews/executions/basic_lookup.yaml")) {
            var fixture = loader.loadExecutionTest(input);
            var normalizer = OperationNormalizer.builder(schema.supergraphSchema())
                .inlineFragments(true).deduplicateFields(true).sortSelections(false)
                .processSkipInclude(true).build();
            var planned = new OperationPlanner(schema.graph()).plan(Operation.parse(fixture.query(), normalizer));
            var withoutPaths = ExecutionPlan.of(planned.steps().stream().map(step -> new ExecutionStep(
                step.id(), step.subgraph(), step.operation(), step.dependsOn(), step.parallelWith(),
                step.requirements(), step.repeatedExecution(), step.artificialFieldPaths(),
                step.requestedFieldPaths())).toList());
            assertThat(withoutPaths.steps()).anySatisfy(step -> assertThat(step.repeatedExecution()).isTrue());

            Map<String, ExecutingMockSubgraphClient> mocks = new LinkedHashMap<>();
            Map<String, SubgraphClient> clients = new LinkedHashMap<>();
            for (String subgraph : planned.steps().stream().map(ExecutionStep::subgraph).distinct().toList()) {
                var mock = new ExecutingMockSubgraphClient(subgraph, schema.getSubgraphSchema(subgraph),
                    fixture.subgraphCalls().stream().filter(call -> call.subgraph().equals(subgraph)).toList());
                mocks.put(subgraph, mock);
                clients.put(subgraph, mock);
            }

            // A plan the planner cannot produce is a programming error: fail the execution loudly.
            assertThatThrownBy(() -> new Executor(clients).execute(withoutPaths, fixture.variables()).block())
                .isInstanceOf(Executor.ExecutionException.class)
                .hasMessageContaining("has no entity path");
            // Only root steps ran; no lookup was sent.
            int rootSteps = (int) planned.steps().stream().filter(ExecutionStep::isRoot).count();
            int calls = mocks.values().stream().mapToInt(m -> m.getRecordedCalls().size()).sum();
            assertThat(calls).isEqualTo(rootSteps);
        }
    }
}
