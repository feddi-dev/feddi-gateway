package dev.feddi.federation.app;

import dev.feddi.federation.customization.FeddiGatewayDefinition;
import dev.feddi.federation.customization.FeddiGatewayDefinitionSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Loads the initial feddi Gateway definition and applies subsequent updates from the active source.
 */
@Component
public class FeddiGatewayDefinitionSourceManager implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FeddiGatewayDefinitionSourceManager.class);

    private final FeddiGatewayDefinitionSource gatewayDefinitionSource;
    private final FeddiGatewayReloadService gatewayReloadService;

    public FeddiGatewayDefinitionSourceManager(FeddiGatewayDefinitionSource gatewayDefinitionSource,
                                               FeddiGatewayReloadService gatewayReloadService) {
        this.gatewayDefinitionSource = gatewayDefinitionSource;
        this.gatewayReloadService = gatewayReloadService;
    }

    @Override
    public void run(ApplicationArguments args) {
        gatewayDefinitionSource.load().ifPresentOrElse(
            this::reloadInitialDefinition,
            () -> log.info("No feddi Gateway definition provided by {}", gatewayDefinitionSource.getClass().getName())
        );

        gatewayDefinitionSource.updates()
            .concatMap(this::reloadUpdatedDefinition)
            .subscribe(
                unused -> { },
                e -> log.error("Gateway definition source stopped publishing updates", e)
            );
    }

    private void reloadInitialDefinition(FeddiGatewayDefinition gatewayDefinition) {
        log.info("Loading feddi Gateway definition from {}", gatewayDefinitionSource.getClass().getName());
        gatewayReloadService.reload(gatewayDefinition);
        log.info("feddi Gateway initialized with {} subgraph(s)", gatewayDefinition.subgraphs().size());
    }

    private Mono<Void> reloadUpdatedDefinition(FeddiGatewayDefinition gatewayDefinition) {
        return Mono.fromRunnable(() -> {
                log.info("Refreshing feddi Gateway definition from {}", gatewayDefinitionSource.getClass().getName());
                gatewayReloadService.reload(gatewayDefinition);
                log.info("feddi Gateway refreshed with {} subgraph(s)", gatewayDefinition.subgraphs().size());
            })
            .then()
            .onErrorResume(e -> {
                log.error("Failed to refresh feddi Gateway definition", e);
                return Mono.<Void>empty();
            });
    }
}
