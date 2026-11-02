package dev.feddi.federation.engine.executor;

import java.time.Duration;

/**
 * Exception thrown when a subgraph call times out.
 */
public class SubgraphTimeoutException extends RuntimeException {

    private final String subgraphName;

    public SubgraphTimeoutException(String subgraphName, Duration timeout) {
        super("Subgraph '" + subgraphName + "' timed out after " + timeout.toMillis() + "ms");
        this.subgraphName = subgraphName;
    }

    public SubgraphTimeoutException(String subgraphName, Duration timeout, Throwable cause) {
        super("Subgraph '" + subgraphName + "' timed out after " + timeout.toMillis() + "ms", cause);
        this.subgraphName = subgraphName;
    }

    public String subgraphName() {
        return subgraphName;
    }

}
