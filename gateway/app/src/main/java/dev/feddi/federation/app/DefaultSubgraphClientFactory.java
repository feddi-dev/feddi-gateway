package dev.feddi.federation.app;

import dev.feddi.federation.extension.SubgraphClient;
import dev.feddi.federation.extension.FeddiGatewaySettings;
import dev.feddi.federation.extension.SubgraphClientFactory;
import dev.feddi.federation.extension.SubgraphRequestHeaderCustomizer;
import dev.feddi.federation.extension.SubgraphSettings;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.EventExecutor;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslOptions;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import javax.net.ssl.SSLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    private final @Nullable SubgraphRequestHeaderCustomizer headerCustomizer;

    /** Spring Boot's SSL bundles ({@code spring.ssl.bundle.*}), for subgraphs with an {@code sslBundle}. */
    private final @Nullable SslBundles sslBundles;

    /** Connection pool settings. */
    private record PoolSettings(int maxConnections, long maxIdleTimeMs) {
    }

    /**
     * Clients by their pool settings, shared by the subgraphs (one pool per subgraph address in each). A definition
     * with other settings gets new pools; the few earlier ones stay, their idle connections closed after their
     * maximum idle time.
     */
    private final Map<PoolSettings, HttpClient> clients = new ConcurrentHashMap<>();

    /**
     * Clients by their pool settings and event loop, for calls made on an event loop: each loop has pools of its
     * own, so a subgraph's response is read on the loop of the request that called it.
     */
    private final Map<PoolSettings, Map<EventExecutor, HttpClient>> loopClients = new ConcurrentHashMap<>();

    public DefaultSubgraphClientFactory(@Nullable SubgraphRequestHeaderCustomizer headerCustomizer) {
        this(headerCustomizer, null);
    }

    public DefaultSubgraphClientFactory(@Nullable SubgraphRequestHeaderCustomizer headerCustomizer,
                                         @Nullable SslBundles sslBundles) {
        this.headerCustomizer = headerCustomizer;
        this.sslBundles = sslBundles;
    }

    @Override
    public SubgraphClient create(String subgraphName, SubgraphSettings settings) {
        Map<String, Object> config = settings.config();
        String url = config.get("url").toString();
        long maxResponseBytes = number(config, FeddiGatewayReloadService.MAX_RESPONSE_BYTES,
            FeddiGatewaySettings.DEFAULT_MAX_RESPONSE_BYTES);
        long connectTimeoutMs = number(config, FeddiGatewayReloadService.CONNECT_TIMEOUT_MS,
            FeddiGatewaySettings.DEFAULT_CONNECT_TIMEOUT_MS);
        PoolSettings pool = new PoolSettings(
            (int) number(config, FeddiGatewayReloadService.MAX_CONNECTIONS, FeddiGatewaySettings.DEFAULT_MAX_CONNECTIONS),
            number(config, FeddiGatewayReloadService.MAX_IDLE_TIME_MS, FeddiGatewaySettings.DEFAULT_MAX_IDLE_TIME_MS));
        int connectTimeout = (int) Math.min(connectTimeoutMs, Integer.MAX_VALUE);
        HttpClient httpClient = clients.computeIfAbsent(pool, DefaultSubgraphClientFactory::client)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout);
        Map<EventExecutor, HttpClient> byLoop = loopClients.computeIfAbsent(pool, p -> new ConcurrentHashMap<>());
        return new DefaultSubgraphClient(httpClient,
            loop -> byLoop.computeIfAbsent(loop, l -> client(pool))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout),
            url, subgraphName, headerCustomizer, maxResponseBytes, sslContext(subgraphName, config.get(SSL_BUNDLE)));
    }

    private static HttpClient client(PoolSettings settings) {
        Duration maxIdleTime = Duration.ofMillis(settings.maxIdleTimeMs());
        return HttpClient.create(ConnectionProvider.builder("feddi-subgraphs")
            .maxConnections(settings.maxConnections())
            // Requests wait for a connection (up to the acquire timeout), as in Reactor Netty's default pool; a
            // built pool fails them once more than twice its connections wait, which load reaches easily
            .pendingAcquireMaxCount(-1)
            .maxIdleTime(maxIdleTime)
            .evictInBackground(maxIdleTime)
            .build());
    }

    private static long number(Map<String, Object> config, String key, long defaultValue) {
        return config.get(key) instanceof Number number ? number.longValue() : defaultValue;
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
