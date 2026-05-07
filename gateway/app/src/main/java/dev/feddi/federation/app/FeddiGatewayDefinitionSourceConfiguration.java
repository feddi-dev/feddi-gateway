package dev.feddi.federation.app;

import dev.feddi.federation.extension.FeddiGatewayDefinitionSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the default feddi Gateway definition source and admin upload.
 *
 * <p>When no extension-provided {@link FeddiGatewayDefinitionSource} is registered,
 * this creates the default in-memory source and the admin upload infrastructure
 * (ZIP upload service, controller, and admin HTTP server).
 *
 * <p>When an extension registers a definition source (e.g. the platform extension),
 * none of these beans are created — the admin upload endpoint is not started.
 */
@AutoConfiguration
@ConditionalOnMissingBean(FeddiGatewayDefinitionSource.class)
public class FeddiGatewayDefinitionSourceConfiguration {

    @Bean
    public DefaultFeddiGatewayDefinitionSource gatewayDefinitionSource() {
        return new DefaultFeddiGatewayDefinitionSource();
    }

    @Bean
    public ZipUploadService zipUploadService(DefaultFeddiGatewayDefinitionSource source) {
        return new ZipUploadService(source);
    }

    @Bean
    public ZipUploadController zipUploadController(ZipUploadService service) {
        return new ZipUploadController(service);
    }

    @Bean
    public AdminServer adminServer(ZipUploadController controller, FeddiGatewayConfigFile config) {
        return new AdminServer(controller, config);
    }
}
