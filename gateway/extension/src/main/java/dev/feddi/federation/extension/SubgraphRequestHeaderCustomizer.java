package dev.feddi.federation.extension;

import org.springframework.http.HttpHeaders;

/**
 * Customizes the HTTP headers sent with a subgraph request.
 *
 * <p>Implementations of this interface are discovered via Spring's component scanning.
 * If a bean is found, the feddi Gateway's default {@code SubgraphClient} calls it after
 * forwarding its built-in {@code Authorization} and {@code User-Agent} headers, with a
 * mutable {@link HttpHeaders} instance — so {@code headers.set(...)} can add a new header
 * or override one already forwarded.
 *
 * <p>To provide an extension implementation:
 * <ol>
 *   <li>Implement this interface</li>
 *   <li>Mark the implementation with {@code @Component} or define it as a {@code @Bean}</li>
 *   <li>Ensure your implementation is on the classpath and scanned by Spring</li>
 * </ol>
 *
 * <p>This extension point only applies to the feddi Gateway's default subgraph client.
 * An extension-provided {@link SubgraphClientFactory} is under no obligation to call it.
 *
 * <p>Example implementation:
 * <pre>{@code
 * @Component
 * public class InternalAuthHeaderCustomizer implements SubgraphRequestHeaderCustomizer {
 *     @Override
 *     public void customize(HttpHeaders headers, String subgraphName, FeddiGatewayRequestContext context) {
 *         headers.set("Authorization", "Bearer " + internalToken());
 *     }
 * }
 * }</pre>
 */
public interface SubgraphRequestHeaderCustomizer {

    /**
     * Called after the built-in headers have been forwarded, immediately before the
     * subgraph request is sent.
     *
     * @param headers the mutable request headers; add or override entries directly
     * @param subgraphName the name of the subgraph the request is being sent to
     * @param context the gateway request context the subgraph call originated from
     */
    void customize(HttpHeaders headers, String subgraphName, FeddiGatewayRequestContext context);
}
