package e2e.extensions;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.feddi.federation.extension.ConfigurableExtension;
import dev.feddi.federation.extension.SubgraphClient;
import dev.feddi.federation.extension.SubgraphClientFactory;
import dev.feddi.federation.extension.SubgraphSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Auto-configuration for the extension-provided Java HttpClient-based SubgraphClientFactory.
 * Implements {@link ConfigurableExtension} to receive timeout configuration from feddi-gateway.yml.
 */
@AutoConfiguration
public class JavaHttpSubgraphClientFactoryAutoConfiguration implements ConfigurableExtension<E2eExtensionConfig> {

    private static final Logger logger = LoggerFactory.getLogger(JavaHttpSubgraphClientFactoryAutoConfiguration.class);

    private int timeoutSeconds = 10;

    @Override
    public String configNamespace() {
        return "e2e";
    }

    @Override
    public Class<E2eExtensionConfig> configType() {
        return E2eExtensionConfig.class;
    }

    @Override
    public void onConfigLoaded(E2eExtensionConfig config) {
        this.timeoutSeconds = config.getTimeoutSeconds();
        logger.info("[EXTENSION FACTORY] Configured from feddi-gateway.yml: timeout={}s", timeoutSeconds);
    }

    @Bean
    @Primary
    public SubgraphClientFactory javaHttpSubgraphClientFactory() {
        logger.info("[EXTENSION FACTORY] JavaHttpSubgraphClientFactory LOADED (timeout={}s)", timeoutSeconds);

        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(timeoutSeconds))
            .build();
        ObjectMapper objectMapper = new ObjectMapper();

        return new SubgraphClientFactory() {
            @Override
            public SubgraphClient create(String subgraphName, SubgraphSettings settings) {
                String url = settings.config().get("url").toString();
                logger.info("[EXTENSION FACTORY] Creating client for subgraph '{}' at URL: {}",
                    subgraphName, url);
                return new JavaHttpSubgraphClient(httpClient, objectMapper, url, subgraphName);
            }
        };
    }
}
