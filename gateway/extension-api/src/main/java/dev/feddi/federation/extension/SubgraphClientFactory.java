package dev.feddi.federation.extension;

/**
 * Factory interface for creating SubgraphClient instances.
 *
 * <p>Implementations of this interface are discovered via Spring's component scanning
 * and used by the feddi Gateway to create clients for communicating with subgraphs.
 *
 * <p>To provide an extension implementation:
 * <ol>
 *   <li>Implement this interface</li>
 *   <li>Mark the implementation with {@code @Component} or define it as a {@code @Bean}</li>
 *   <li>Ensure your implementation is on the classpath and scanned by Spring</li>
 * </ol>
 *
 * <p>The feddi Gateway provides a default implementation that will be used if no extension-provided
 * implementation is found.
 *
 * <p>Example implementation:
 * <pre>{@code
 * @Component
 * public class MySubgraphClientFactory implements SubgraphClientFactory {
 *     @Override
 *     public SubgraphClient create(String subgraphName, SubgraphSettings settings) {
 *         String url = settings.config().get("url").toString();
 *         return new MySubgraphClient(subgraphName, url);
 *     }
 * }
 * }</pre>
 */
public interface SubgraphClientFactory {

    /**
     * Creates a SubgraphClient for the given subgraph.
     *
     * @param subgraphName the name of the subgraph (for logging, metrics, etc.)
     * @param settings the settings for the subgraph
     * @return a SubgraphClient configured for the specified subgraph
     */
    SubgraphClient create(String subgraphName, SubgraphSettings settings);
}
