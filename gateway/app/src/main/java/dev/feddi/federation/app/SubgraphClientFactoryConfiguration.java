package dev.feddi.federation.app;

import dev.feddi.federation.extension.SubgraphClientFactory;
import dev.feddi.federation.extension.SubgraphRequestHeaderCustomizer;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.netty.http.client.HttpClient;

/**
 * Configuration for SubgraphClientFactory beans.
 *
 * <p>Provides a default SubgraphClientFactory if no extension-provided implementation is found.
 * To provide an extension implementation, create a Spring bean that implements
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
    public SubgraphClientFactory subgraphClientFactory(@Nullable SubgraphRequestHeaderCustomizer headerCustomizer,
                                                         ObjectProvider<SslBundles> sslBundles) {
        return new DefaultSubgraphClientFactory(HttpClient.create(), headerCustomizer, sslBundles.getIfAvailable());
    }
}
