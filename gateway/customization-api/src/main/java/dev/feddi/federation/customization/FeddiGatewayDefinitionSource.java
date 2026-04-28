package dev.feddi.federation.customization;

import reactor.core.publisher.Flux;

import java.util.Optional;

/**
 * Provides feddi Gateway definitions from any source.
 *
 * <p>Implementations of this interface are discovered via Spring's component scanning
 * and used by the feddi Gateway to initialize itself on startup and receive refreshes over time.
 *
 * <p>To provide a custom implementation:
 * <ol>
 *   <li>Implement this interface</li>
 *   <li>Mark the implementation with {@code @Component} or define it as a {@code @Bean}</li>
 *   <li>Ensure your implementation is on the classpath and scanned by Spring</li>
 * </ol>
 *
 * <p>The feddi Gateway provides a default in-memory implementation.
 */
public interface FeddiGatewayDefinitionSource {

    /**
     * Loads the feddi Gateway definition to use at startup.
     *
     * @return the feddi Gateway definition, or empty if startup should leave the gateway uninitialized
     */
    Optional<FeddiGatewayDefinition> load();

    /**
     * Publishes updated feddi Gateway definitions over time.
     *
     * @return a stream of definition updates
     */
    Flux<FeddiGatewayDefinition> updates();
}
