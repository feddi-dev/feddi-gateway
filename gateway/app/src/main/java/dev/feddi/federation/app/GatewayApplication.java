package dev.feddi.federation.app;

import dev.feddi.federation.customization.ExtensionConfigProvider;
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
public class GatewayApplication {

    private static GatewayConfigFile gatewayConfig;

    public static void main(String[] args) {
        // Log version before anything else
        String version = "dev";
        try (var is = GatewayApplication.class.getResourceAsStream("/gateway-version.txt")) {
            if (is != null) version = new String(is.readAllBytes()).trim();
        } catch (Exception ignored) {}
        org.slf4j.LoggerFactory.getLogger(GatewayApplication.class)
            .info("feddi Gateway version: {}", version);

        // Load feddi-gateway.yml before Spring context starts
        gatewayConfig = GatewayConfigLoader.load();

        // Configure file logging based on feddi-gateway.yml
        LoggingConfigurer.configure(gatewayConfig.getLogging().getDir());

        // Set server port from feddi-gateway.yml
        System.setProperty("server.port", String.valueOf(gatewayConfig.getPort()));

        // Introspection toggle
        System.setProperty("gateway.introspection.enabled",
                String.valueOf(gatewayConfig.isIntrospectionEnabled()));

        // Actuator on a separate port, bound to localhost by default
        System.setProperty("management.server.port", String.valueOf(gatewayConfig.getManagementPort()));
        System.setProperty("management.server.address", gatewayConfig.getManagementAddress());

        // Set system properties for each extension namespace so auto-configurations
        // can use @ConditionalOnProperty to activate only when configured
        for (String namespace : gatewayConfig.getExtensions().keySet()) {
            System.setProperty("gateway.extensions." + namespace, "true");
        }

        SpringApplication.run(GatewayApplication.class, args);
    }

    private static GatewayConfigFile getOrLoadConfig() {
        if (gatewayConfig == null) {
            // @SpringBootTest doesn't call main() — load config on demand
            gatewayConfig = GatewayConfigLoader.load();
        }
        return gatewayConfig;
    }

    @Bean
    public GatewayConfigFile gatewayConfigFile() {
        return getOrLoadConfig();
    }

    @Bean
    public ExtensionConfigProvider extensionConfigProvider() {
        return new DefaultExtensionConfigProvider(getOrLoadConfig().getExtensions());
    }
}
