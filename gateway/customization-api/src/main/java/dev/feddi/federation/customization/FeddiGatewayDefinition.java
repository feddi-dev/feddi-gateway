package dev.feddi.federation.customization;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Full gateway definition returned by a {@link FeddiGatewayDefinitionSource}.
 *
 * @param subgraphs subgraph definitions keyed by subgraph name
 * @param gatewaySettings gateway-level settings
 * @param supergraphSdl pre-composed supergraph SDL from the control plane, or null if
 *                       the gateway should compose from subgraph schemas itself
 */
public record FeddiGatewayDefinition(
    Map<String, SubgraphDefinition> subgraphs,
    FeddiGatewaySettings gatewaySettings,
    String supergraphSdl
) {
    public FeddiGatewayDefinition {
        subgraphs = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(subgraphs, "subgraphs")));
        gatewaySettings = gatewaySettings != null ? gatewaySettings : FeddiGatewaySettings.defaults();
    }

    /**
     * Convenience constructor without pre-composed supergraph (gateway composes itself).
     */
    public FeddiGatewayDefinition(Map<String, SubgraphDefinition> subgraphs, FeddiGatewaySettings gatewaySettings) {
        this(subgraphs, gatewaySettings, null);
    }
}
