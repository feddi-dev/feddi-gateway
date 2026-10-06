package dev.feddi.federation.app;

import dev.feddi.federation.extension.FeddiGatewayDefinition;
import dev.feddi.federation.extension.FeddiGatewayDefinitionSource;
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
            // A reload can take a while (variable batching checks). Keep only the newest pending
            // definition meanwhile; the source never sees backpressure.
            .onBackpressureLatest()
            .concatMap(this::reloadUpdatedDefinition)
            .subscribe(
                unused -> { },
                e -> log.error("Gateway definition source stopped publishing updates", e)
            );
    }

    private void reloadInitialDefinition(FeddiGatewayDefinition gatewayDefinition) {
        log.info("Loading feddi Gateway definition from {}", gatewayDefinitionSource.getClass().getName());
        // Startup: an invalid definition must stop the application
        gatewayReloadService.reload(gatewayDefinition).block();
        log.info("feddi Gateway initialized with {} subgraph(s)", gatewayDefinition.subgraphs().size());
    }

    private Mono<Void> reloadUpdatedDefinition(FeddiGatewayDefinition gatewayDefinition) {
        return Mono.defer(() -> {
                log.info("Refreshing feddi Gateway definition from {}", gatewayDefinitionSource.getClass().getName());
                return gatewayReloadService.reload(gatewayDefinition);
            })
            .doOnSuccess(unused ->
                log.info("feddi Gateway refreshed with {} subgraph(s)", gatewayDefinition.subgraphs().size()))
            .onErrorResume(e -> {
                log.error("Failed to refresh feddi Gateway definition", e);
                return Mono.<Void>empty();
            });
    }
}
