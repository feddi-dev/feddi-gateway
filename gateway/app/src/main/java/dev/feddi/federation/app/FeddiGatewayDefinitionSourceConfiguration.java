package dev.feddi.federation.app;

import dev.feddi.federation.customization.FeddiGatewayDefinitionSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the default gateway definition source and admin upload.
 *
 * <p>When no custom {@link FeddiGatewayDefinitionSource} is registered by an extension,
 * this creates the default in-memory source and the admin upload infrastructure
 * (ZIP upload service, controller, and admin HTTP server).
 *
 * <p>When an extension registers a custom source (e.g. the platform extension),
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
