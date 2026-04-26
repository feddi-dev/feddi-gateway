package dev.feddi.federation.customization;

import java.util.Objects;

/**
 * Complete definition of a subgraph used to build the gateway.
 *
 * @param sdl the subgraph SDL
 * @param settings the subgraph settings
 */
public record SubgraphDefinition(String sdl, SubgraphSettings settings) {
    public SubgraphDefinition {
        Objects.requireNonNull(sdl, "sdl");
        Objects.requireNonNull(settings, "settings");
    }
}
