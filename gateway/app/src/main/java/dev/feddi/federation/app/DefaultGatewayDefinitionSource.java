package dev.feddi.federation.app;

import dev.feddi.federation.customization.GatewayDefinition;
import dev.feddi.federation.customization.GatewayDefinitionSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Default in-memory gateway definition source.
 */
public class DefaultGatewayDefinitionSource implements GatewayDefinitionSource {

    private final AtomicReference<GatewayDefinition> current = new AtomicReference<>();
    private final Sinks.Many<GatewayDefinition> updates = Sinks.many().multicast().directBestEffort();

    @Override
    public Optional<GatewayDefinition> load() {
        return Optional.ofNullable(current.get());
    }

    @Override
    public Flux<GatewayDefinition> updates() {
        return updates.asFlux();
    }

    /**
     * Replaces the current definition and publishes it to subscribers.
     */
    public void replace(GatewayDefinition gatewayDefinition) {
        current.set(gatewayDefinition);
        Sinks.EmitResult emitResult = updates.tryEmitNext(gatewayDefinition);
        if (emitResult.isFailure() && emitResult != Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
            throw new GatewayDefinitionException("Failed to publish gateway definition update: " + emitResult);
        }
    }
}
