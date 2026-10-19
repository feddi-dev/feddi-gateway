package dev.feddi.federation.app;

import dev.feddi.federation.extension.SubgraphClient;
import dev.feddi.federation.extension.FeddiGatewaySettings;
import dev.feddi.federation.extension.SubgraphClientFactory;
import dev.feddi.federation.extension.SubgraphRequestHeaderCustomizer;
import dev.feddi.federation.extension.SubgraphSettings;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.ReferenceCountUtil;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslOptions;
import reactor.netty.http.client.HttpClient;

import javax.net.ssl.SSLException;
import java.util.Arrays;

/**
 * Default factory for creating DefaultSubgraphClient instances.
 *
 * <p>This is used when no extension-provided SubgraphClientFactory bean is found.
 * Extension implementations can provide one by creating a Spring bean that implements
 * {@link SubgraphClientFactory} and marking it with {@code @Component} or
 * defining it in a {@code @Configuration} class.
 */
public class DefaultSubgraphClientFactory implements SubgraphClientFactory {

    /** The subgraph config key naming a Spring Boot SSL bundle for the subgraph's TLS connections. */
    static final String SSL_BUNDLE = "sslBundle";

    private final HttpClient httpClient;
    private final @Nullable SubgraphRequestHeaderCustomizer headerCustomizer;

    /** Spring Boot's SSL bundles ({@code spring.ssl.bundle.*}), for subgraphs with an {@code sslBundle}. */
    private final @Nullable SslBundles sslBundles;

    public DefaultSubgraphClientFactory(HttpClient httpClient,
                                         @Nullable SubgraphRequestHeaderCustomizer headerCustomizer) {
        this(httpClient, headerCustomizer, null);
    }

    public DefaultSubgraphClientFactory(HttpClient httpClient,
                                         @Nullable SubgraphRequestHeaderCustomizer headerCustomizer,
                                         @Nullable SslBundles sslBundles) {
        this.httpClient = httpClient;
        this.headerCustomizer = headerCustomizer;
        this.sslBundles = sslBundles;
    }

    @Override
    public SubgraphClient create(String subgraphName, SubgraphSettings settings) {
        String url = settings.config().get("url").toString();
        long maxResponseBytes = settings.config().get(FeddiGatewayReloadService.MAX_RESPONSE_BYTES) instanceof Number limit
            ? limit.longValue() : FeddiGatewaySettings.DEFAULT_MAX_RESPONSE_BYTES;
        return new DefaultSubgraphClient(httpClient, url, subgraphName, headerCustomizer, maxResponseBytes,
            sslContext(subgraphName, settings.config().get(SSL_BUNDLE)));
    }

    /**
     * The TLS context of a subgraph's {@code sslBundle}: its key and trust material, protocols and ciphers. Null if
     * the subgraph has none (the JDK's defaults apply).
     *
     * @throws IllegalArgumentException if the bundle does not exist or cannot be used
     */
    private @Nullable SslContext sslContext(String subgraphName, @Nullable Object bundleName) {
        if (bundleName == null) {
            return null;
        }
        if (sslBundles == null) {
            throw new IllegalArgumentException("Subgraph " + subgraphName + " has sslBundle '" + bundleName
                + "', but no SSL bundles are configured (spring.ssl.bundle.*)");
        }
        try {
            SslBundle bundle = sslBundles.getBundle(bundleName.toString());
            SslContextBuilder builder = SslContextBuilder.forClient()
                .keyManager(bundle.getManagers().getKeyManagerFactory())
                .trustManager(bundle.getManagers().getTrustManagerFactory());
            SslOptions options = bundle.getOptions();
            if (options.getEnabledProtocols() != null) {
                builder.protocols(options.getEnabledProtocols());
            }
            if (options.getCiphers() != null) {
                builder.ciphers(Arrays.asList(options.getCiphers()));
            }
            SslContext context = builder.build();
            // Protocols and ciphers are checked when an engine is created: fail the definition, not every call
            ReferenceCountUtil.release(context.newEngine(UnpooledByteBufAllocator.DEFAULT));
            return context;
        } catch (NoSuchSslBundleException e) {
            throw new IllegalArgumentException("Subgraph " + subgraphName + " has an unknown sslBundle: " + bundleName, e);
        } catch (SSLException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Subgraph " + subgraphName + " cannot use sslBundle " + bundleName
                + ": " + e.getMessage(), e);
        }
    }
}
