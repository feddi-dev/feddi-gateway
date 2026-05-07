package dev.feddi.federation.app;

import dev.feddi.federation.extension.FeddiGatewayDefinition;
import dev.feddi.federation.extension.FeddiGatewayDefinitionSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Default in-memory gateway definition source.
 */
public class DefaultFeddiGatewayDefinitionSource implements FeddiGatewayDefinitionSource {

    private final AtomicReference<FeddiGatewayDefinition> current = new AtomicReference<>();
    private final Sinks.Many<FeddiGatewayDefinition> updates = Sinks.many().multicast().directBestEffort();

    @Override
    public Optional<FeddiGatewayDefinition> load() {
        return Optional.ofNullable(current.get());
    }

    @Override
    public Flux<FeddiGatewayDefinition> updates() {
        return updates.asFlux();
    }

    /**
     * Replaces the current definition and publishes it to subscribers.
     */
    public void replace(FeddiGatewayDefinition gatewayDefinition) {
        current.set(gatewayDefinition);
        Sinks.EmitResult emitResult = updates.tryEmitNext(gatewayDefinition);
        if (emitResult.isFailure() && emitResult != Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
            throw new FeddiGatewayDefinitionException("Failed to publish gateway definition update: " + emitResult);
        }
    }
}
