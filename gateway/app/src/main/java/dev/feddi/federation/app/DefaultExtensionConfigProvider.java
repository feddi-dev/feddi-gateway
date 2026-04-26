package dev.feddi.federation.app;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.feddi.federation.customization.ExtensionConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;

/**
 * Default implementation of {@link ExtensionConfigProvider} backed by
 * the extensions map from gateway.yml.
 */
public class DefaultExtensionConfigProvider implements ExtensionConfigProvider {

    private static final Logger log = LoggerFactory.getLogger(DefaultExtensionConfigProvider.class);

    private final Map<String, Object> extensions;
    private final ObjectMapper objectMapper;

    public DefaultExtensionConfigProvider(Map<String, Object> extensions) {
        this.extensions = extensions;
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public <T> Optional<T> get(String namespace, Class<T> type) {
        Object raw = extensions.get(namespace);
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.convertValue(raw, type));
        } catch (Exception e) {
            log.error("Failed to parse extension config for '{}': {}", namespace, e.getMessage());
            return Optional.empty();
        }
    }
}
