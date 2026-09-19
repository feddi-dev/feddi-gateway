package dev.feddi.federation.app;

import dev.feddi.federation.extension.SubgraphClient;
import dev.feddi.federation.extension.SubgraphClientFactory;
import dev.feddi.federation.extension.SubgraphRequestHeaderCustomizer;
import dev.feddi.federation.extension.SubgraphSettings;
import org.jspecify.annotations.Nullable;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Default factory for creating DefaultSubgraphClient instances.
 *
 * <p>This is used when no extension-provided SubgraphClientFactory bean is found.
 * Extension implementations can provide one by creating a Spring bean that implements
 * {@link SubgraphClientFactory} and marking it with {@code @Component} or
 * defining it in a {@code @Configuration} class.
 */
public class DefaultSubgraphClientFactory implements SubgraphClientFactory {

    private final WebClient.Builder webClientBuilder;
    private final @Nullable SubgraphRequestHeaderCustomizer headerCustomizer;

    public DefaultSubgraphClientFactory(WebClient.Builder webClientBuilder,
                                         @Nullable SubgraphRequestHeaderCustomizer headerCustomizer) {
        this.webClientBuilder = webClientBuilder;
        this.headerCustomizer = headerCustomizer;
    }

    @Override
    public SubgraphClient create(String subgraphName, SubgraphSettings settings) {
        String url = settings.config().get("url").toString();
        WebClient webClient = webClientBuilder.baseUrl(url).build();
        return new DefaultSubgraphClient(webClient, subgraphName, headerCustomizer);
    }
}
