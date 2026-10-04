package dev.feddi.federation.app;

import dev.feddi.federation.extension.DocumentProvider;
import dev.feddi.federation.extension.FeddiGatewayDefinition;
import dev.feddi.federation.extension.SubgraphClient;
import dev.feddi.federation.extension.SubgraphClientFactory;
import dev.feddi.federation.extension.SubgraphDefinition;
import dev.feddi.federation.engine.executor.BatchingOptions;
import dev.feddi.federation.extension.SubgraphSettings;
import dev.feddi.federation.engine.compose.Composer.SubgraphInput;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds the feddi Gateway from a logical gateway definition.
 */
@Service
public class FeddiGatewayReloadService {

    private final FeddiGatewayHolder gatewayHolder;
    private static final Logger log = LoggerFactory.getLogger(FeddiGatewayReloadService.class);
    private static final Duration VARIABLE_BATCHING_CHECK_TIMEOUT = Duration.ofSeconds(10);

    private final SubgraphClientFactory clientFactory;
    private final FeddiGatewayMetrics gatewayMetrics;
    private final DocumentProvider documentProvider;
    private final boolean introspectionEnabled;

    public FeddiGatewayReloadService(FeddiGatewayHolder gatewayHolder, SubgraphClientFactory clientFactory,
                                     FeddiGatewayMetrics gatewayMetrics,
                                     @Nullable DocumentProvider documentProvider,
                                     FeddiGatewayConfigFile gatewayConfigFile) {
        this.gatewayHolder = gatewayHolder;
        this.clientFactory = clientFactory;
        this.gatewayMetrics = gatewayMetrics;
        this.documentProvider = documentProvider;
        this.introspectionEnabled = gatewayConfigFile.isIntrospectionEnabled();
    }

    /**
     * Rebuilds and swaps the active gateway.
     *
     * @param gatewayDefinition logical feddi Gateway definition
     */
    public void reload(FeddiGatewayDefinition gatewayDefinition) {
        if (gatewayDefinition == null) {
            throw new FeddiGatewayDefinitionException("Gateway definition must not be null");
        }
        if (gatewayDefinition.subgraphs().isEmpty()) {
            throw new FeddiGatewayDefinitionException("No subgraphs defined");
        }

        List<SubgraphInput> inputs = new ArrayList<>();
        Map<String, SubgraphClient> clients = new HashMap<>();
        Map<String, BatchingOptions> batching = new HashMap<>();
        Map<String, DefaultSubgraphClient> variableBatchingClients = new HashMap<>();
        Duration timeout = gatewayDefinition.gatewaySettings().timeout();

        for (Map.Entry<String, SubgraphDefinition> entry : gatewayDefinition.subgraphs().entrySet()) {
            String name = entry.getKey();
            SubgraphDefinition subgraphDefinition = entry.getValue();
            validateSubgraph(name, subgraphDefinition);

            SubgraphSettings settings = subgraphDefinition.settings();
            String url = settings.config().get("url") != null ? settings.config().get("url").toString() : "";
            inputs.add(new SubgraphInput(name, url, subgraphDefinition.sdl()));

            try {
                batching.put(name, BatchingOptions.fromSettings(settings.config(), BatchingOptions.NONE));
            } catch (IllegalArgumentException e) {
                throw new FeddiGatewayDefinitionException(
                    "Invalid batching config for subgraph " + name + ": " + e.getMessage(), e);
            }

            SubgraphClient baseClient = clientFactory.create(name, settings);
            if (batching.get(name).mode() == BatchingOptions.Mode.VARIABLES) {
                if (baseClient instanceof DefaultSubgraphClient defaultClient) {
                    variableBatchingClients.put(name, defaultClient);
                } else {
                    log.warn("Subgraph '{}' is configured with 'batching: variables' but uses a custom "
                        + "SubgraphClient ({}). Variable batching only takes effect if it implements "
                        + "executeBatch; otherwise every entity is sent separately. 'batching: alias' "
                        + "works with any client.", name, baseClient.getClass().getName());
                }
            }
            clients.put(name, new TimeoutAwareSubgraphClient(baseClient, name, timeout));
        }

        FeddiFederationGateway gateway;
        if (gatewayDefinition.supergraphSdl() != null) {
            // Pre-composed supergraph from control plane — skip composition
            gateway = FeddiFederationGateway.createWithPreComposedSupergraph(
                    gatewayDefinition.supergraphSdl(), inputs, clients,
                    gatewayMetrics, gatewayMetrics, documentProvider, introspectionEnabled);
        } else {
            // No pre-composed supergraph — compose from subgraph schemas
            gateway = FeddiFederationGateway.create(inputs, clients, gatewayMetrics,
                    gatewayMetrics, documentProvider, introspectionEnabled);
        }
        FeddiFederationGateway configured = gateway.withSubgraphBatching(batching);
        gatewayHolder.set(configured);
        verifyVariableBatching(configured, variableBatchingClients, batching);
    }

    /**
     * Checks asynchronously that subgraphs configured with {@code batching: variables} support it.
     * Reload may run on an event-loop thread, so this never blocks. A subgraph that answers but
     * does not support variable batching falls back to alias batching (spec-compliant, works with
     * every server) with an error in the log; an unreachable subgraph is left as configured.
     */
    private void verifyVariableBatching(FeddiFederationGateway gateway, Map<String, DefaultSubgraphClient> clients,
                                        Map<String, BatchingOptions> batching) {
        if (clients.isEmpty()) {
            return;
        }
        Flux.fromIterable(clients.entrySet())
            .flatMap(entry -> entry.getValue().supportsVariableBatching()
                .timeout(VARIABLE_BATCHING_CHECK_TIMEOUT)
                .filter(supported -> !supported)
                .map(unsupported -> entry.getKey())
                .onErrorResume(e -> {
                    log.warn("Could not verify variable batching support of subgraph '{}': {}",
                        entry.getKey(), e.getMessage());
                    return Mono.empty();
                }))
            .collectList()
            .filter(unsupported -> !unsupported.isEmpty())
            .subscribe(unsupported -> {
                log.error("Subgraph(s) {} are configured with 'batching: variables' but do not support variable "
                    + "batching. Falling back to 'batching: alias'. Set 'batching: alias' or 'none' for them.",
                    unsupported);
                Map<String, BatchingOptions> fallback = new HashMap<>(batching);
                for (String name : unsupported) {
                    fallback.put(name, new BatchingOptions(BatchingOptions.Mode.ALIAS, batching.get(name).maxBatchSize()));
                }
                gatewayHolder.replace(gateway, gateway.withSubgraphBatching(fallback));
            });
    }

    private void validateSubgraph(String name, SubgraphDefinition subgraphDefinition) {
        if (subgraphDefinition == null) {
            throw new FeddiGatewayDefinitionException("Missing definition for subgraph: " + name);
        }
        if (subgraphDefinition.sdl().isBlank()) {
            throw new FeddiGatewayDefinitionException("Missing SDL for subgraph: " + name);
        }
        if (subgraphDefinition.settings() == null) {
            throw new FeddiGatewayDefinitionException("Missing settings for subgraph: " + name);
        }
        Object url = subgraphDefinition.settings().config().get("url");
        if (url == null || url.toString().isBlank()) {
            throw new FeddiGatewayDefinitionException("Missing URL in config for subgraph: " + name);
        }
    }
}
