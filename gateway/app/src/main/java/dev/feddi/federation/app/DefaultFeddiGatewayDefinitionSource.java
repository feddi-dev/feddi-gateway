package dev.feddi.federation.app;

import dev.feddi.federation.extension.FeddiGatewayDefinition;
import dev.feddi.federation.extension.FeddiGatewayDefinitionSource;
import reactor.core.publisher.Flux;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Default in-memory gateway definition source, used when no extension provides one.
 *
 * <p>It publishes no updates: ZIP uploads activate their definition directly through the
 * {@link FeddiGatewayReloadService} (see {@link ZipUploadService}), so the uploader learns
 * whether activation succeeded. The source only remembers the active definition.
 */
public class DefaultFeddiGatewayDefinitionSource implements FeddiGatewayDefinitionSource {

    private final AtomicReference<FeddiGatewayDefinition> current = new AtomicReference<>();

    @Override
    public Optional<FeddiGatewayDefinition> load() {
        return Optional.ofNullable(current.get());
    }

    @Override
    public Flux<FeddiGatewayDefinition> updates() {
        return Flux.never();
    }

    /**
     * Remembers a definition after it was activated.
     */
    public void store(FeddiGatewayDefinition gatewayDefinition) {
        current.set(gatewayDefinition);
    }
}
