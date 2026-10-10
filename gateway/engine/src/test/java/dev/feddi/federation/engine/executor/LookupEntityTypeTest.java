package dev.feddi.federation.engine.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.feddi.federation.engine.benchmark.SimulatedSubgraphClient;
import dev.feddi.federation.engine.compose.Composer;
import dev.feddi.federation.engine.compose.CompositionResult;
import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.oracle.DeterministicData;
import dev.feddi.federation.engine.planner.ExecutionPlan;
import dev.feddi.federation.engine.planner.ExecutionStep;
import dev.feddi.federation.engine.planner.OperationPlanner;
import dev.feddi.federation.engine.query.Operation;
import dev.feddi.federation.engine.query.OperationNormalizer;
import graphql.ExecutionResult;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A lookup below a type condition ({@code products { ... on Book { reviewsCount } }}) only runs for
 * entities of that type: the Magazine items of the list must neither be sent to {@code bookById}
 * nor get {@code reviewsCount}. The same holds for a type condition further up the entity path
 * ({@code reviews { ... on UserReview { product { inStock } } }}).
 */
class LookupEntityTypeTest {

    private static final String QUERY = "{ products { ... on Book { reviewsCount } } }";

    private static final String BRANCH_QUERY = "{ reviews { ... on AnonymousReview { product { price } } "
        + "... on UserReview { product { inStock } } } }";

    @Test
    void lookupBelowTypeConditionRunsOnlyForThatType() throws Exception {
        CompositionResult composition = compose("schemas/abstract_types/schema.yaml");
        ExecutionPlan plan = plan(composition, QUERY);

        ExecutionStep lookup = plan.steps().stream().filter(ExecutionStep::repeatedExecution).findFirst().orElseThrow();
        assertThat(lookup.entityTypes()).containsExactly("Book");

        DeterministicData data = new DeterministicData(composition.subgraphs(), composition.supergraph());
        Map<String, SimulatedSubgraphClient> simulated = new LinkedHashMap<>();
        Map<String, SubgraphClient> clients = new LinkedHashMap<>();
        for (Subgraph subgraph : composition.subgraphs()) {
            var client = new SimulatedSubgraphClient(data.executable(subgraph.schema()), 0);
            simulated.put(subgraph.name(), client);
            clients.put(subgraph.name(), client);
        }
        ExecutionResult result = new Executor(clients, composition.supergraph()).execute(plan, Map.of()).block();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> products = (List<Map<String, Object>>) ((Map<String, Object>) result.getData()).get("products");
        ExecutionResult monolith = graphql.GraphQL.newGraphQL(data.executable(composition.supergraph())).build().execute(QUERY);
        assertThat((Object) result.getData()).isEqualTo(monolith.getData());
        assertThat(products).anyMatch(Map::isEmpty);  // a Magazine: nothing selected for it
        int lookups = simulated.get(lookup.subgraph()).calls().size();
        assertThat(lookups).isEqualTo((int) products.stream().filter(p -> !p.isEmpty()).count());
    }

    @Test
    void lookupBelowTypeConditionOnAParentRunsOnlyInThatBranch() throws Exception {
        CompositionResult composition = compose("schemas/union_branch_lookups/schema.yaml");
        ExecutionPlan plan = plan(composition, BRANCH_QUERY);

        ExecutionStep pricing = plan.steps().stream().filter(s -> s.subgraph().equals("pricing")).findFirst().orElseThrow();
        assertThat(pricing.entityPath()).containsExactly("reviews", "product");
        assertThat(pricing.entityPathTypes()).isEqualTo(Map.of(1, Set.of("AnonymousReview")));

        DeterministicData data = new DeterministicData(composition.subgraphs(), composition.supergraph());
        Map<String, SubgraphClient> clients = new LinkedHashMap<>();
        for (Subgraph subgraph : composition.subgraphs()) {
            clients.put(subgraph.name(), new SimulatedSubgraphClient(data.executable(subgraph.schema()), 0));
        }
        ExecutionResult result = new Executor(clients, composition.supergraph()).execute(plan, Map.of()).block();
        ExecutionResult monolith = graphql.GraphQL.newGraphQL(data.executable(composition.supergraph())).build()
            .execute(BRANCH_QUERY);
        assertThat((Object) result.getData()).isEqualTo(monolith.getData());
        assertThat(result.getData().toString()).contains("price").contains("inStock");  // both members occur
    }

    private static ExecutionPlan plan(CompositionResult composition, String query) {
        return new OperationPlanner(composition.graph()).plan(Operation.parse(query,
            OperationNormalizer.builder(composition.supergraph()).inlineFragments(true).deduplicateFields(true)
                .sortSelections(false).processSkipInclude(true).build()));
    }

    private static CompositionResult compose(String resource) throws Exception {
        List<Composer.SubgraphInput> inputs = new ArrayList<>();
        try (InputStream in = LookupEntityTypeTest.class.getClassLoader().getResourceAsStream(resource)) {
            new ObjectMapper(new YAMLFactory()).readTree(in).path("subgraphs").properties()
                .forEach(e -> inputs.add(Composer.SubgraphInput.of(e.getKey(), e.getValue().asText())));
        }
        return new Composer().compose(inputs);
    }
}
