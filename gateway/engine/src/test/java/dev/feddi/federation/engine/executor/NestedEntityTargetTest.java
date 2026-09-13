package dev.feddi.federation.engine.executor;

import dev.feddi.federation.engine.planner.OperationPlanner;
import dev.feddi.federation.engine.query.Operation;
import dev.feddi.federation.engine.query.OperationNormalizer;
import dev.feddi.federation.engine.testcase.TestCaseLoader;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NestedEntityTargetTest {
    @ParameterizedTest
    @ValueSource(strings = {"overlapping_ids", "aliases", "hidden_keys", "null_author",
        "null_lookup", "empty_list", "null_list", "sibling_aliases"})
    void onlyFetchesUsersAtTheSelectedResponsePath(String scenario) throws Exception {
        var loader = new TestCaseLoader();
        var schema = loader.loadSchemaFromClasspath("schemas/nested_entity_targets/schema.yaml");
        try (var input = getClass().getResourceAsStream(
                "/schemas/nested_entity_targets/executions/" + scenario + ".yaml")) {
            var fixture = loader.loadExecutionTest(input);
            var normalizer = OperationNormalizer.builder(schema.supergraphSchema())
                .inlineFragments(true).deduplicateFields(true).sortSelections(false)
                .processSkipInclude(true).build();
            var plan = new OperationPlanner(schema.graph()).plan(Operation.parse(fixture.query(), normalizer));
            Map<String, SubgraphClient> clients = new LinkedHashMap<>();
            for (String subgraph : List.of("products", "reviews", "users")) {
                clients.put(subgraph, new ExecutingMockSubgraphClient(subgraph,
                    schema.getSubgraphSchema(subgraph), fixture.subgraphCalls().stream()
                        .filter(call -> call.subgraph().equals(subgraph)).toList()));
            }
            var result = new Executor(clients).execute(plan, fixture.variables()).block();
            assertThat(result.getErrors()).isEmpty();
            assertThat((Object) result.getData()).isEqualTo(fixture.expectedResponse().get("data"));

            var users = (ExecutingMockSubgraphClient) clients.get("users");
            var ids = users.getRecordedCalls().stream().map(call -> call.variables().get("id")).toList();
            if (scenario.equals("empty_list") || scenario.equals("null_list")) {
                assertThat(ids).isEmpty();
            } else {
                // Review IDs must never produce extra lookups, even when they equal a user ID.
                assertThat(ids).containsExactlyInAnyOrder("1", "2");
            }
            var paths = plan.steps().stream().filter(step -> step.subgraph().equals("users"))
                .map(step -> step.entityPath()).toList();
            if (scenario.equals("aliases")) {
                assertThat(paths).containsExactly(List.of("item", "feedback", "writer"));
            } else if (scenario.equals("sibling_aliases")) {
                assertThat(paths).containsExactlyInAnyOrder(
                    List.of("first", "reviews", "author"), List.of("second", "reviews", "author"));
            } else {
                assertThat(paths).containsExactly(List.of("product", "reviews", "author"));
            }
        }
    }
}
