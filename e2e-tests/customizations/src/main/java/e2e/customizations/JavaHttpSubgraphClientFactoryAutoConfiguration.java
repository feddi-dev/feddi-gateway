package e2e.customizations;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.feddi.federation.customization.ConfigurableExtension;
import dev.feddi.federation.customization.SubgraphClient;
import dev.feddi.federation.customization.SubgraphClientFactory;
import dev.feddi.federation.customization.SubgraphSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Auto-configuration for the custom Java HttpClient-based SubgraphClientFactory.
 * Implements {@link ConfigurableExtension} to receive timeout configuration from feddi-gateway.yml.
 */
@AutoConfiguration
public class JavaHttpSubgraphClientFactoryAutoConfiguration implements ConfigurableExtension<E2eCustomizationConfig> {

    private static final Logger logger = LoggerFactory.getLogger(JavaHttpSubgraphClientFactoryAutoConfiguration.class);

    private int timeoutSeconds = 10;

    @Override
    public String configNamespace() {
        return "e2e";
    }

    @Override
    public Class<E2eCustomizationConfig> configType() {
        return E2eCustomizationConfig.class;
    }

    @Override
    public void onConfigLoaded(E2eCustomizationConfig config) {
        this.timeoutSeconds = config.getTimeoutSeconds();
        logger.info("[CUSTOM FACTORY] Configured from feddi-gateway.yml: timeout={}s", timeoutSeconds);
    }

    @Bean
    @Primary
    public SubgraphClientFactory javaHttpSubgraphClientFactory() {
        logger.info("[CUSTOM FACTORY] JavaHttpSubgraphClientFactory LOADED (timeout={}s)", timeoutSeconds);

        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(timeoutSeconds))
            .build();
        ObjectMapper objectMapper = new ObjectMapper();

        return new SubgraphClientFactory() {
            @Override
            public SubgraphClient create(String subgraphName, SubgraphSettings settings) {
                String url = settings.config().get("url").toString();
                logger.info("[CUSTOM FACTORY] Creating client for subgraph '{}' at URL: {}",
                    subgraphName, url);
                return new JavaHttpSubgraphClient(httpClient, objectMapper, url, subgraphName);
            }
        };
    }
}
