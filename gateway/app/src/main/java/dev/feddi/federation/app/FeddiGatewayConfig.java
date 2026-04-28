package dev.feddi.federation.app;

import dev.feddi.federation.customization.UsageReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.http.codec.CodecCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Spring configuration for the gateway.
 */
@Configuration
public class FeddiGatewayConfig {

    private static final Logger log = LoggerFactory.getLogger(FeddiGatewayConfig.class);

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    /**
     * Configures the max in-memory buffer size for request body reading.
     * This enforces the request size limit at the byte level — works for
     * chunked requests and ignores Content-Length headers.
     */
    @Bean
    public CodecCustomizer maxRequestSizeCodecCustomizer(FeddiGatewayConfigFile config) {
        return configurer -> {
            long maxBytes = config.getMaxRequestSizeBytes();
            if (maxBytes > 0) {
                log.info("Max request size: {} bytes ({}KB)", maxBytes, maxBytes / 1024);
                configurer.defaultCodecs().maxInMemorySize((int) maxBytes);
            }
        };
    }

    /**
     * Default no-op usage reporter. Extensions can provide their own implementation
     * which will take precedence via @Primary or @ConditionalOnMissingBean.
     */
    @Bean
    @ConditionalOnMissingBean(UsageReporter.class)
    public UsageReporter noOpUsageReporter() {
        return (context, outcome) -> {}; // silently discard
    }
}
