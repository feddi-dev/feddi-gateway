package dev.feddi.federation.app;

import dev.feddi.federation.extension.SubgraphClient;
import dev.feddi.federation.extension.SubgraphClientFactory;
import dev.feddi.federation.extension.SubgraphRequestHeaderCustomizer;
import dev.feddi.federation.extension.SubgraphSettings;
import org.jspecify.annotations.Nullable;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * Default factory for creating DefaultSubgraphClient instances.
 *
 * <p>This is used when no extension-provided SubgraphClientFactory bean is found.
 * Extension implementations can provide one by creating a Spring bean that implements
 * {@link SubgraphClientFactory} and marking it with {@code @Component} or
 * defining it in a {@code @Configuration} class.
 */
public class DefaultSubgraphClientFactory implements SubgraphClientFactory {

    private final HttpClient httpClient;
    private final @Nullable SubgraphRequestHeaderCustomizer headerCustomizer;
    /** Experiment: builds a WebClient per subgraph, if set. */
    private final WebClient.@Nullable Builder webClientBuilder;

    public DefaultSubgraphClientFactory(HttpClient httpClient,
                                         @Nullable SubgraphRequestHeaderCustomizer headerCustomizer) {
        this(httpClient, headerCustomizer, null);
    }

    public DefaultSubgraphClientFactory(HttpClient httpClient,
                                         @Nullable SubgraphRequestHeaderCustomizer headerCustomizer,
                                         WebClient.@Nullable Builder webClientBuilder) {
        this.httpClient = httpClient;
        this.headerCustomizer = headerCustomizer;
        this.webClientBuilder = webClientBuilder;
    }

    @Override
    public SubgraphClient create(String subgraphName, SubgraphSettings settings) {
        String url = settings.config().get("url").toString();
        WebClient webClient = webClientBuilder == null ? null : webClientBuilder.clone()
            .baseUrl(url)
            .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(64 * 1024 * 1024))
            .build();
        return new DefaultSubgraphClient(httpClient, url, subgraphName, headerCustomizer, webClient);
    }
}
