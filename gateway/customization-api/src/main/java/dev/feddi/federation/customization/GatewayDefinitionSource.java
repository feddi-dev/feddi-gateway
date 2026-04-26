package dev.feddi.federation.customization;

import reactor.core.publisher.Flux;

import java.util.Optional;

/**
 * Provides gateway definitions from any source.
 *
 * <p>Implementations of this interface are discovered via Spring's component scanning
 * and used by the gateway to initialize itself on startup and receive refreshes over time.
 *
 * <p>To provide a custom implementation:
 * <ol>
 *   <li>Implement this interface</li>
 *   <li>Mark the implementation with {@code @Component} or define it as a {@code @Bean}</li>
 *   <li>Ensure your implementation is on the classpath and scanned by Spring</li>
 * </ol>
 *
 * <p>The gateway provides a default in-memory implementation.
 */
public interface GatewayDefinitionSource {

    /**
     * Loads the gateway definition to use at startup.
     *
     * @return the gateway definition, or empty if startup should leave the gateway uninitialized
     */
    Optional<GatewayDefinition> load();

    /**
     * Publishes updated gateway definitions over time.
     *
     * @return a stream of definition updates
     */
    Flux<GatewayDefinition> updates();
}
