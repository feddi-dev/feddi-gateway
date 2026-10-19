package dev.feddi.federation.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.feddi.federation.extension.FeddiGatewayDefinition;
import dev.feddi.federation.extension.FeddiGatewaySettings;
import dev.feddi.federation.extension.SubgraphClientFactory;
import dev.feddi.federation.extension.SubgraphDefinition;
import dev.feddi.federation.extension.SubgraphSettings;
import graphql.ExecutionResultImpl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The effective {@code maxResponseBytes} of each subgraph, as client factories find it in the subgraph's config. */
class SubgraphResponseLimitConfigTest {

    private static final String SDL = "type Query { products: [String] }";

    @Test
    void defaultsTo8Megabytes() {
        assertThat(limit(FeddiGatewaySettings.defaults(), Map.of())).isEqualTo(8L * 1024 * 1024);
        assertThat(limit(new FeddiGatewaySettings(1000L), Map.of())).isEqualTo(8L * 1024 * 1024);
    }

    @Test
    void gatewaySettingAppliesToEverySubgraph() {
        assertThat(limit(new FeddiGatewaySettings(null, 1024L), Map.of())).isEqualTo(1024L);
    }

    @Test
    void subgraphSettingOverridesTheGateways() {
        assertThat(limit(new FeddiGatewaySettings(null, 1024L), Map.of("maxResponseBytes", 33554432)))
            .isEqualTo(33554432L);
    }

    @Test
    void rootConfigReadsTheLimitAndStaysCompatible() throws Exception {
        // As ZipUploadService reads the definition's root config.yaml
        var yaml = new ObjectMapper(new YAMLFactory());
        assertThat(yaml.readValue("timeoutMs: 5000\nmaxResponseBytes: 2048\n", FeddiGatewaySettings.class))
            .isEqualTo(new FeddiGatewaySettings(5000L, 2048L));
        FeddiGatewaySettings old = yaml.readValue("timeoutMs: 5000\n", FeddiGatewaySettings.class);
        assertThat(old.responseLimit()).isEqualTo(FeddiGatewaySettings.DEFAULT_MAX_RESPONSE_BYTES);
        assertThat(old.timeout()).hasMillis(5000);
    }

    @Test
    void invalidLimitsAreRejected() {
        for (Object invalid : new Object[] {0, -1, "8MB", 1.5}) {
            assertThatThrownBy(() -> limit(FeddiGatewaySettings.defaults(), Map.of("maxResponseBytes", invalid)))
                .as("%s", invalid)
                .isInstanceOf(FeddiGatewayDefinitionException.class)
                .hasMessageContaining("maxResponseBytes for subgraph catalog");
        }
        assertThatThrownBy(() -> limit(new FeddiGatewaySettings(null, 0L), Map.of()))
            .isInstanceOf(FeddiGatewayDefinitionException.class)
            .hasMessageContaining("maxResponseBytes");
    }

    /** The limit the client factory gets for a subgraph with these settings. */
    private static Object limit(FeddiGatewaySettings gatewaySettings, Map<String, Object> subgraphSettings) {
        Map<String, Object> received = new HashMap<>();
        SubgraphClientFactory factory = (name, settings) -> {
            received.putAll(settings.config());
            return (PerEntitySubgraphClient) (operation, variables, context) ->
                Mono.just(ExecutionResultImpl.newExecutionResult().build());
        };
        Map<String, Object> config = new HashMap<>(subgraphSettings);
        config.put("url", "http://catalog.local/graphql");
        var service = new FeddiGatewayReloadService(new FeddiGatewayHolder(), factory,
            new FeddiGatewayMetrics(new SimpleMeterRegistry()), null, new FeddiGatewayConfigFile());
        service.reload(new FeddiGatewayDefinition(
            Map.of("catalog", new SubgraphDefinition(SDL, new SubgraphSettings(config))), gatewaySettings)).block();
        return received.get("maxResponseBytes");
    }
}
