package dev.feddi.federation.engine.executor;

import dev.feddi.federation.engine.planner.ExecutionPlan;
import dev.feddi.federation.engine.planner.ExecutionStep;
import dev.feddi.federation.engine.planner.OperationPlanner;
import dev.feddi.federation.engine.query.Operation;
import dev.feddi.federation.engine.query.OperationNormalizer;
import dev.feddi.federation.engine.testcase.ExecutionTest;
import dev.feddi.federation.engine.testcase.SchemaDefinition;
import dev.feddi.federation.engine.testcase.TestCaseLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The executor relies on two properties of plans from the OperationPlanner. Plans that break
 * them can only be assembled by hand; they fail the execution loudly instead of being run with
 * guesswork:
 * <ul>
 *   <li>Every dependent step is repeated (an entity lookup with key requirements). Lookups
 *       without a key are rejected at composition (LOOKUP_MUST_HAVE_ARGUMENTS).</li>
 *   <li>Every repeated step has an entity path. Without it the executor would have to search the
 *       response for objects with matching key fields, which targets unrelated objects (#49).</li>
 * </ul>
 */
class ExecutionPlanInvariantsTest {

    private SchemaDefinition schema;
    private ExecutionTest fixture;
    private ExecutionPlan planned;

    @BeforeEach
    void planFixture() throws Exception {
        var loader = new TestCaseLoader();
        schema = loader.loadSchemaFromClasspath("schemas/products_reviews/schema.yaml");
        try (var input = getClass().getResourceAsStream("/schemas/products_reviews/executions/basic_lookup.yaml")) {
            fixture = loader.loadExecutionTest(input);
        }
        var normalizer = OperationNormalizer.builder(schema.supergraphSchema())
            .inlineFragments(true).deduplicateFields(true).sortSelections(false)
            .processSkipInclude(true).build();
        planned = new OperationPlanner(schema.graph()).plan(Operation.parse(fixture.query(), normalizer));
        assertThat(planned.steps()).anySatisfy(step -> assertThat(step.repeatedExecution()).isTrue());
    }

    @Test
    void repeatedStepWithoutEntityPathFails() {
        var plan = transform(step -> new ExecutionStep(step.id(), step.subgraph(), step.operation(),
            step.dependsOn(), step.parallelWith(), step.requirements(), step.repeatedExecution(),
            step.artificialFieldPaths(), step.requestedFieldPaths()));

        assertFailsWithoutLookups(plan, "has no entity path");
    }

    @Test
    void dependentStepWithoutRequirementsFails() {
        var plan = transform(step -> step.isRoot() ? step : new ExecutionStep(step.id(), step.subgraph(),
            step.operation(), step.dependsOn(), step.parallelWith(), Map.of(), false,
            step.artificialFieldPaths(), step.requestedFieldPaths(), step.entityPath()));

        assertFailsWithoutLookups(plan, "has no requirements");
    }

    private ExecutionPlan transform(UnaryOperator<ExecutionStep> change) {
        return ExecutionPlan.of(planned.steps().stream().map(change).toList());
    }

    private void assertFailsWithoutLookups(ExecutionPlan plan, String message) {
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
            .hasMessageContaining(message);
        // Only root steps ran; no lookup was sent.
        int rootSteps = (int) planned.steps().stream().filter(ExecutionStep::isRoot).count();
        int calls = mocks.values().stream().mapToInt(m -> m.getRecordedCalls().size()).sum();
        assertThat(calls).isEqualTo(rootSteps);
    }
}
