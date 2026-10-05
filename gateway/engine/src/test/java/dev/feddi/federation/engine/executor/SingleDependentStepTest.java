package dev.feddi.federation.engine.executor;

import dev.feddi.federation.engine.compose.Composer;
import dev.feddi.federation.engine.compose.Composer.SubgraphInput;
import dev.feddi.federation.engine.planner.OperationPlanner;
import dev.feddi.federation.engine.query.Operation;
import dev.feddi.federation.engine.query.OperationNormalizer;
import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A @lookup field without arguments has no key, so the planner creates a dependent step without
 * requirements that runs once (not per entity). Such lookups are invalid per the federation spec
 * (LOOKUP_MUST_HAVE_ARGUMENTS) but still compose today; this covers how they execute.
 */
class SingleDependentStepTest {

    @Test
    void lookupWithoutArgumentsRunsOnceAfterItsDependency() {
        var composed = new Composer().compose(List.of(
            new SubgraphInput("a", "http://a", """
                type Query { settings: Settings @lookup }
                type Settings @key(fields: "id") { id: ID! theme: String }
                """),
            new SubgraphInput("b", "http://b", """
                type Query { settings: Settings @lookup }
                type Settings @key(fields: "id") { id: ID! locale: String }
                """)));
        assertThat(composed.isSuccess()).isTrue();
        var normalizer = OperationNormalizer.builder(composed.supergraph()).inlineFragments(true)
            .deduplicateFields(true).sortSelections(false).processSkipInclude(true).build();
        var plan = new OperationPlanner(composed.graph()).plan(Operation.parse("{ settings { theme locale } }", normalizer));
        assertThat(plan.steps()).anySatisfy(step -> {
            assertThat(step.dependsOn()).isNotEmpty();
            assertThat(step.repeatedExecution()).isFalse();
        });

        Map<String, SubgraphClient> clients = Map.of(
            "a", (operation, variables) -> respond("theme", "dark"),
            "b", (operation, variables) -> respond("locale", "de"));
        var result = new Executor(clients).execute(plan, Map.of()).block();

        assertThat(result.getErrors()).isEmpty();
        assertThat((Object) result.getData()).isEqualTo(Map.of("settings", Map.of("theme", "dark", "locale", "de")));
    }

    /** A subgraph response with mutable maps, like parsed JSON. */
    private static Mono<ExecutionResult> respond(String field, String value) {
        Map<String, Object> settings = new HashMap<>();
        settings.put(field, value);
        Map<String, Object> data = new HashMap<>();
        data.put("settings", settings);
        return Mono.just(ExecutionResultImpl.newExecutionResult().data(data).build());
    }
}
