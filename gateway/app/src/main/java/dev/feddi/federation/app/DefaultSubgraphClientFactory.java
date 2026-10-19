package dev.feddi.federation.app;

import dev.feddi.federation.extension.SubgraphClient;
import dev.feddi.federation.extension.FeddiGatewaySettings;
import dev.feddi.federation.extension.SubgraphClientFactory;
import dev.feddi.federation.extension.SubgraphRequestHeaderCustomizer;
import dev.feddi.federation.extension.SubgraphSettings;
import org.jspecify.annotations.Nullable;
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

    public DefaultSubgraphClientFactory(HttpClient httpClient,
                                         @Nullable SubgraphRequestHeaderCustomizer headerCustomizer) {
        this.httpClient = httpClient;
        this.headerCustomizer = headerCustomizer;
    }

    @Override
    public SubgraphClient create(String subgraphName, SubgraphSettings settings) {
        String url = settings.config().get("url").toString();
        long maxResponseBytes = settings.config().get(FeddiGatewayReloadService.MAX_RESPONSE_BYTES) instanceof Number limit
            ? limit.longValue() : FeddiGatewaySettings.DEFAULT_MAX_RESPONSE_BYTES;
        return new DefaultSubgraphClient(httpClient, url, subgraphName, headerCustomizer, maxResponseBytes);
    }
}
