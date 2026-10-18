package dev.feddi.federation.app;

import dev.feddi.federation.extension.SubgraphClientFactory;
import dev.feddi.federation.extension.SubgraphRequestHeaderCustomizer;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
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
                                                         ObjectProvider<WebClient.Builder> webClientBuilder) {
        // Experiment: -Dfeddi.webclient=plain (a WebClient without Spring Boot's customizations) or boot (Spring
        // Boot's builder, with its observation); otherwise the HttpClient
        String mode = System.getProperty("feddi.webclient", "");
        HttpClient httpClient = HttpClient.create();
        WebClient.Builder builder = switch (mode) {
            case "plain" -> WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient));
            case "boot", "tuned" -> webClientBuilder.getObject();
            default -> null;
        };
        LoggerFactory.getLogger(SubgraphClientFactoryConfiguration.class)
            .info("Subgraph client: {}", builder == null ? "HttpClient" : "WebClient (" + mode + ")");
        return new DefaultSubgraphClientFactory(httpClient, headerCustomizer, builder);
    }
}
