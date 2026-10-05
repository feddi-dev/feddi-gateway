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
 * Dependent steps are entity lookups with key requirements, executed once per entity. Lookups
 * without a key are rejected at composition (LOOKUP_MUST_HAVE_ARGUMENTS), so the planner never
 * creates a dependent step without requirements. A hand-assembled one fails the execution.
 */
class DependentStepRequirementsTest {

    @Test
    void dependentStepWithoutRequirementsFails() throws Exception {
        var loader = new TestCaseLoader();
        var schema = loader.loadSchemaFromClasspath("schemas/products_reviews/schema.yaml");
        try (var input = getClass().getResourceAsStream("/schemas/products_reviews/executions/basic_lookup.yaml")) {
            var fixture = loader.loadExecutionTest(input);
            var normalizer = OperationNormalizer.builder(schema.supergraphSchema())
                .inlineFragments(true).deduplicateFields(true).sortSelections(false)
                .processSkipInclude(true).build();
            var planned = new OperationPlanner(schema.graph()).plan(Operation.parse(fixture.query(), normalizer));
            var plan = ExecutionPlan.of(planned.steps().stream().map(step -> step.isRoot() ? step
                : new ExecutionStep(step.id(), step.subgraph(), step.operation(), step.dependsOn(),
                    step.parallelWith(), Map.of(), false, step.artificialFieldPaths(),
                    step.requestedFieldPaths(), step.entityPath())).toList());

            Map<String, ExecutingMockSubgraphClient> mocks = new LinkedHashMap<>();
            Map<String, SubgraphClient> clients = new LinkedHashMap<>();
            for (String subgraph : planned.steps().stream().map(ExecutionStep::subgraph).distinct().toList()) {
                var mock = new ExecutingMockSubgraphClient(subgraph, schema.getSubgraphSchema(subgraph),
                    fixture.subgraphCalls().stream().filter(call -> call.subgraph().equals(subgraph)).toList());
                mocks.put(subgraph, mock);
                clients.put(subgraph, mock);
            }

            assertThatThrownBy(() -> new Executor(clients).execute(plan, fixture.variables()).block())
                .isInstanceOf(Executor.ExecutionException.class)
                .hasMessageContaining("has no requirements");
            // Only root steps ran; no lookup was sent.
            int rootSteps = (int) planned.steps().stream().filter(ExecutionStep::isRoot).count();
            int calls = mocks.values().stream().mapToInt(m -> m.getRecordedCalls().size()).sum();
            assertThat(calls).isEqualTo(rootSteps);
        }
    }
}
