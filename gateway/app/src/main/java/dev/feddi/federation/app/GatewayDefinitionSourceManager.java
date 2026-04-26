package dev.feddi.federation.app;

import dev.feddi.federation.customization.GatewayDefinition;
import dev.feddi.federation.customization.GatewayDefinitionSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Loads the initial gateway definition and applies subsequent updates from the active source.
 */
@Component
public class GatewayDefinitionSourceManager implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GatewayDefinitionSourceManager.class);

    private final GatewayDefinitionSource gatewayDefinitionSource;
    private final GatewayReloadService gatewayReloadService;

    public GatewayDefinitionSourceManager(GatewayDefinitionSource gatewayDefinitionSource,
                                          GatewayReloadService gatewayReloadService) {
        this.gatewayDefinitionSource = gatewayDefinitionSource;
        this.gatewayReloadService = gatewayReloadService;
    }

    @Override
    public void run(ApplicationArguments args) {
        gatewayDefinitionSource.load().ifPresentOrElse(
            this::reloadInitialDefinition,
            () -> log.info("No gateway definition provided by {}", gatewayDefinitionSource.getClass().getName())
        );

        gatewayDefinitionSource.updates()
            .concatMap(this::reloadUpdatedDefinition)
            .subscribe(
                unused -> { },
                e -> log.error("Gateway definition source stopped publishing updates", e)
            );
    }

    private void reloadInitialDefinition(GatewayDefinition gatewayDefinition) {
        log.info("Loading gateway definition from {}", gatewayDefinitionSource.getClass().getName());
        gatewayReloadService.reload(gatewayDefinition);
        log.info("Gateway initialized with {} subgraph(s)", gatewayDefinition.subgraphs().size());
    }

    private Mono<Void> reloadUpdatedDefinition(GatewayDefinition gatewayDefinition) {
        return Mono.fromRunnable(() -> {
                log.info("Refreshing gateway definition from {}", gatewayDefinitionSource.getClass().getName());
                gatewayReloadService.reload(gatewayDefinition);
                log.info("Gateway refreshed with {} subgraph(s)", gatewayDefinition.subgraphs().size());
            })
            .then()
            .onErrorResume(e -> {
                log.error("Failed to refresh gateway definition", e);
                return Mono.<Void>empty();
            });
    }
}
