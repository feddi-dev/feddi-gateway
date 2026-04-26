package dev.feddi.federation.customization;

import java.util.Optional;

/**
 * Provides typed access to extension configuration from gateway.yml.
 *
 * <p>Extensions inject this to read their configuration at bean creation time:
 * <pre>
 * {@literal @}Bean
 * public MyService myService(ExtensionConfigProvider configProvider) {
 *     var config = configProvider.get("my-extension", MyConfig.class);
 *     return config.map(c -> new MyService(c)).orElse(null);
 * }
 * </pre>
 */
public interface ExtensionConfigProvider {

    /**
     * Get the typed configuration for an extension namespace.
     *
     * @param namespace the extension namespace (e.g., "feddi")
     * @param type the configuration class to deserialize into
     * @return the configuration, or empty if no config exists for the namespace
     */
    <T> Optional<T> get(String namespace, Class<T> type);
}
