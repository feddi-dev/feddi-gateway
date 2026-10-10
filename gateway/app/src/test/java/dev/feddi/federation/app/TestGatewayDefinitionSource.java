package dev.feddi.federation.app;

import dev.feddi.federation.extension.FeddiGatewayDefinition;
import dev.feddi.federation.extension.FeddiGatewayDefinitionSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A definition source that publishes updates like a push-based extension source: without a
 * buffer, so it relies on the subscriber to keep requesting.
 */
class TestGatewayDefinitionSource implements FeddiGatewayDefinitionSource {

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
     * Sets the definition returned by {@link #load()} and publishes it.
     */
    Sinks.EmitResult replace(FeddiGatewayDefinition gatewayDefinition) {
        current.set(gatewayDefinition);
        return updates.tryEmitNext(gatewayDefinition);
    }
}
