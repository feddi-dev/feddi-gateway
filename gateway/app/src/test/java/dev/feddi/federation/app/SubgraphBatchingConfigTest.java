package dev.feddi.federation.app;

import dev.feddi.federation.engine.executor.BatchingOptions;
import dev.feddi.federation.engine.executor.BatchingOptions.Mode;
import dev.feddi.federation.engine.executor.SubgraphTimeoutException;
import dev.feddi.federation.extension.FeddiGatewayDefinition;
import dev.feddi.federation.extension.FeddiGatewayRequestContext;
import dev.feddi.federation.extension.FeddiGatewaySettings;
import dev.feddi.federation.extension.SubgraphClient;
import dev.feddi.federation.extension.SubgraphClientFactory;
import dev.feddi.federation.extension.SubgraphDefinition;
import dev.feddi.federation.extension.SubgraphSettings;
import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.language.OperationDefinition;
import graphql.parser.Parser;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubgraphBatchingConfigTest {

    private static final String SDL = """
        type Query {
          products: [Product]
        }
        type Product {
          id: ID!
        }
        """;

    private static final OperationDefinition OPERATION = Parser.parse("query ($id: ID!) { products { id } }")
        .getDefinitionsOfType(OperationDefinition.class).get(0);

    @Test
    void reloadAppliesBatchingFromSubgraphSettings() {
        var holder = reload(Map.of("url", "http://catalog.local/graphql", "batching", "variables", "batch-max-size", 16));

        assertThat(holder.get().batching("catalog")).isEqualTo(new BatchingOptions(Mode.VARIABLES, 16));
        assertThat(holder.get().batching("unknown")).isEqualTo(BatchingOptions.NONE);
    }

    @Test
    void subgraphsWithoutBatchingSettingDoNotBatch() {
        var holder = reload(Map.of("url", "http://catalog.local/graphql"));

        assertThat(holder.get().batching("catalog")).isEqualTo(BatchingOptions.NONE);
    }

    @Test
    void invalidBatchingSettingIsRejected() {
        assertThatThrownBy(() -> reload(Map.of("url", "http://catalog.local/graphql", "batching", "sometimes")))
            .isInstanceOf(FeddiGatewayDefinitionException.class)
            .hasMessageContaining("catalog")
            .hasMessageContaining("expected none, alias or variables");
    }

    @Test
    void invalidBatchSizeIsRejected() {
        assertThatThrownBy(() -> reload(Map.of("url", "http://catalog.local/graphql", "batch-max-size", "many")))
            .isInstanceOf(FeddiGatewayDefinitionException.class)
            .hasMessageContaining("batch-max-size");
    }

    @Test
    void adapterPassesBatchesAndContextToTheExtensionClient() {
        List<FeddiGatewayRequestContext> contexts = new ArrayList<>();
        SubgraphClient client = new SubgraphClient() {
            @Override
            public Mono<ExecutionResult> execute(OperationDefinition operation, Map<String, Object> variables,
                                                 FeddiGatewayRequestContext context) {
                contexts.add(context);
                return Mono.just(ExecutionResultImpl.newExecutionResult().data(variables).build());
            }
        };
        var context = FeddiGatewayRequestContext.empty();
        var options = new BatchingOptions(Mode.ALIAS, 8);
        var adapter = new SubgraphClientAdapter(client, context, options);

        List<ExecutionResult> results = adapter.executeBatch(OPERATION, List.of(Map.of("id", "1"), Map.of("id", "2"))).block();

        assertThat(results).extracting(r -> (Object) r.getData()).containsExactly(Map.of("id", "1"), Map.of("id", "2"));
        assertThat(contexts).containsOnly(context).hasSize(2);
        assertThat(adapter.batching()).isEqualTo(options);
    }

    @Test
    void timeoutAppliesToBatches() {
        SubgraphClient slow = (operation, variables, context) -> Mono.never();
        var client = new TimeoutAwareSubgraphClient(slow, "catalog", Duration.ofMillis(50));

        assertThatThrownBy(() -> client.executeBatch(OPERATION, List.of(Map.of("id", "1")),
            FeddiGatewayRequestContext.empty()).block())
            .isInstanceOf(SubgraphTimeoutException.class);
        assertThat(client.subgraphName()).isEqualTo("catalog");
        assertThat(client.timeout()).isEqualTo(Duration.ofMillis(50));
    }

    private static FeddiGatewayHolder reload(Map<String, Object> settings) {
        SubgraphClientFactory factory = (name, subgraphSettings) -> (operation, variables, context) ->
            Mono.just(ExecutionResultImpl.newExecutionResult().build());
        var holder = new FeddiGatewayHolder();
        var service = new FeddiGatewayReloadService(holder, factory,
            new FeddiGatewayMetrics(new SimpleMeterRegistry()), null, new FeddiGatewayConfigFile());
        service.reload(new FeddiGatewayDefinition(
            Map.of("catalog", new SubgraphDefinition(SDL, new SubgraphSettings(settings))),
            FeddiGatewaySettings.defaults()));
        return holder;
    }
}
