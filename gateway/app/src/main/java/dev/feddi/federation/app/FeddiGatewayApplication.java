package dev.feddi.federation.app;

import dev.feddi.federation.extension.ExtensionConfigProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot application for the GraphQL Federation Gateway.
 *
 * Configuration is loaded from feddi-gateway.yml in the working directory.
 * The config file is parsed before the Spring context starts, so logging
 * is set up early.
 */
@SpringBootApplication
public class FeddiGatewayApplication {

    private static FeddiGatewayConfigFile gatewayConfig;

    public static void main(String[] args) {
        // Log version before anything else
        String version = "dev";
        try (var is = FeddiGatewayApplication.class.getResourceAsStream("/feddi-gateway-version.txt")) {
            if (is != null) version = new String(is.readAllBytes()).trim();
        } catch (Exception ignored) {}
        org.slf4j.LoggerFactory.getLogger(FeddiGatewayApplication.class)
            .info("feddi Gateway version: {}", version);

        // Load feddi-gateway.yml before Spring context starts
        gatewayConfig = FeddiGatewayConfigLoader.load();

        // Configure file logging based on feddi-gateway.yml
        LoggingConfigurer.configure(gatewayConfig.getLogging().getDir());

        // Set server port from feddi-gateway.yml
        System.setProperty("server.port", String.valueOf(gatewayConfig.getPort()));

        // Introspection toggle
        System.setProperty("feddi.gateway.introspection.enabled",
                String.valueOf(gatewayConfig.isIntrospectionEnabled()));

        // Actuator on a separate port, bound to localhost by default
        System.setProperty("management.server.port", String.valueOf(gatewayConfig.getManagementPort()));
        System.setProperty("management.server.address", gatewayConfig.getManagementAddress());

        // Set system properties for each extension namespace so auto-configurations
        // can use @ConditionalOnProperty to activate only when configured
        for (String namespace : gatewayConfig.getExtensions().keySet()) {
            System.setProperty("feddi.gateway.extensions." + namespace, "true");
        }

        SpringApplication.run(FeddiGatewayApplication.class, args);
    }

    private static FeddiGatewayConfigFile getOrLoadConfig() {
        if (gatewayConfig == null) {
            // @SpringBootTest doesn't call main() — load config on demand
            gatewayConfig = FeddiGatewayConfigLoader.load();
        }
        return gatewayConfig;
    }

    @Bean
    public FeddiGatewayConfigFile gatewayConfigFile() {
        return getOrLoadConfig();
    }

    @Bean
    public ExtensionConfigProvider extensionConfigProvider() {
        return new DefaultExtensionConfigProvider(getOrLoadConfig().getExtensions());
    }
}
