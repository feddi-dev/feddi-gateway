package dev.feddi.federation.app;

import dev.feddi.federation.customization.SubgraphClientFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Configuration for SubgraphClientFactory beans.
 *
 * <p>Provides a default SubgraphClientFactory if no custom implementation is found.
 * To provide a custom implementation, create a Spring bean that implements
 * {@link SubgraphClientFactory} and mark it with {@code @Component} or define it
 * in a {@code @Configuration} class.
 */
@Configuration
public class SubgraphClientFactoryConfiguration {

    /**
     * Creates the default SubgraphClientFactory if no other is defined.
     *
     * <p>This bean is only created when no other SubgraphClientFactory bean exists.
     */
    @Bean
    @ConditionalOnMissingBean(SubgraphClientFactory.class)
    public SubgraphClientFactory subgraphClientFactory(WebClient.Builder webClientBuilder) {
        return new DefaultSubgraphClientFactory(webClientBuilder);
    }
}
