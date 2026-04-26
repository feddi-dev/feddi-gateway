package dev.feddi.federation.app;

import dev.feddi.federation.customization.DocumentProvider;
import dev.feddi.federation.customization.GatewayDefinition;
import dev.feddi.federation.customization.SubgraphClient;
import dev.feddi.federation.customization.SubgraphClientFactory;
import dev.feddi.federation.customization.SubgraphDefinition;
import dev.feddi.federation.customization.SubgraphSettings;
import dev.feddi.federation.engine.compose.Composer.SubgraphInput;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds the gateway from a logical gateway definition.
 */
@Service
public class GatewayReloadService {

    private final GatewayHolder gatewayHolder;
    private final SubgraphClientFactory clientFactory;
    private final GatewayMetrics gatewayMetrics;
    private final DocumentProvider documentProvider;
    private final boolean introspectionEnabled;

    public GatewayReloadService(GatewayHolder gatewayHolder, SubgraphClientFactory clientFactory,
                                GatewayMetrics gatewayMetrics,
                                @Nullable DocumentProvider documentProvider,
                                GatewayConfigFile gatewayConfigFile) {
        this.gatewayHolder = gatewayHolder;
        this.clientFactory = clientFactory;
        this.gatewayMetrics = gatewayMetrics;
        this.documentProvider = documentProvider;
        this.introspectionEnabled = gatewayConfigFile.isIntrospectionEnabled();
    }

    /**
     * Rebuilds and swaps the active gateway.
     *
     * @param gatewayDefinition logical gateway definition
     */
    public void reload(GatewayDefinition gatewayDefinition) {
        if (gatewayDefinition == null) {
            throw new GatewayDefinitionException("Gateway definition must not be null");
        }
        if (gatewayDefinition.subgraphs().isEmpty()) {
            throw new GatewayDefinitionException("No subgraphs defined");
        }

        List<SubgraphInput> inputs = new ArrayList<>();
        Map<String, SubgraphClient> clients = new HashMap<>();
        Duration timeout = gatewayDefinition.gatewaySettings().timeout();

        for (Map.Entry<String, SubgraphDefinition> entry : gatewayDefinition.subgraphs().entrySet()) {
            String name = entry.getKey();
            SubgraphDefinition subgraphDefinition = entry.getValue();
            validateSubgraph(name, subgraphDefinition);

            SubgraphSettings settings = subgraphDefinition.settings();
            String url = settings.config().get("url") != null ? settings.config().get("url").toString() : "";
            inputs.add(new SubgraphInput(name, url, subgraphDefinition.sdl()));

            SubgraphClient baseClient = clientFactory.create(name, settings);
            clients.put(name, new TimeoutAwareSubgraphClient(baseClient, name, timeout));
        }

        FederationGateway gateway;
        if (gatewayDefinition.supergraphSdl() != null) {
            // Pre-composed supergraph from control plane — skip composition
            gateway = FederationGateway.createWithPreComposedSupergraph(
                    gatewayDefinition.supergraphSdl(), inputs, clients,
                    gatewayMetrics, gatewayMetrics, documentProvider, introspectionEnabled);
        } else {
            // No pre-composed supergraph — compose from subgraph schemas
            gateway = FederationGateway.create(inputs, clients, gatewayMetrics,
                    gatewayMetrics, documentProvider, introspectionEnabled);
        }
        gatewayHolder.set(gateway);
    }

    private void validateSubgraph(String name, SubgraphDefinition subgraphDefinition) {
        if (subgraphDefinition == null) {
            throw new GatewayDefinitionException("Missing definition for subgraph: " + name);
        }
        if (subgraphDefinition.sdl().isBlank()) {
            throw new GatewayDefinitionException("Missing SDL for subgraph: " + name);
        }
        if (subgraphDefinition.settings() == null) {
            throw new GatewayDefinitionException("Missing settings for subgraph: " + name);
        }
        Object url = subgraphDefinition.settings().config().get("url");
        if (url == null || url.toString().isBlank()) {
            throw new GatewayDefinitionException("Missing URL in config for subgraph: " + name);
        }
    }
}
