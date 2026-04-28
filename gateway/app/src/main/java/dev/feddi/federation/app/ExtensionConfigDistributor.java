package dev.feddi.federation.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.feddi.federation.customization.ConfigurableExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Distributes configuration from feddi-gateway.yml to extensions that implement
 * {@link ConfigurableExtension}. Runs early in startup (before
 * {@link FeddiGatewayDefinitionSourceManager}) so extensions are configured
 * before the gateway attempts to load definitions.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ExtensionConfigDistributor implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ExtensionConfigDistributor.class);

    private final FeddiGatewayConfigFile config;
    private final List<ConfigurableExtension<?>> extensions;
    private final ObjectMapper objectMapper;

    public ExtensionConfigDistributor(FeddiGatewayConfigFile config, List<ConfigurableExtension<?>> extensions) {
        this.config = config;
        this.extensions = extensions;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<String, Object> extensionsConfig = config.getExtensions();

        for (var extension : extensions) {
            String namespace = extension.configNamespace();
            Object rawConfig = extensionsConfig.get(namespace);

            if (rawConfig == null) {
                log.info("No configuration for extension '{}' in feddi-gateway.yml", namespace);
                continue;
            }

            try {
                Object typedConfig = objectMapper.convertValue(rawConfig, extension.configType());
                deliverConfig(extension, typedConfig);
                log.info("Configured extension '{}' from feddi-gateway.yml", namespace);
            } catch (Exception e) {
                log.error("Failed to parse configuration for extension '{}': {}", namespace, e.getMessage());
            }
        }

        // Warn about unknown extension namespaces
        for (String namespace : extensionsConfig.keySet()) {
            boolean known = extensions.stream()
                    .anyMatch(ext -> ext.configNamespace().equals(namespace));
            if (!known) {
                log.warn("Unknown extension namespace '{}' in feddi-gateway.yml — no extension registered for it", namespace);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void deliverConfig(ConfigurableExtension<T> extension, Object config) {
        extension.onConfigLoaded((T) config);
    }
}
