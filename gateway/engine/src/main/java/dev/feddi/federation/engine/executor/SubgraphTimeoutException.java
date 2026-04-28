package dev.feddi.federation.engine.executor;

import java.time.Duration;

/**
 * Exception thrown when a subgraph call times out.
 */
public class SubgraphTimeoutException extends RuntimeException {

    private final String subgraphName;
    private final Duration timeout;

    public SubgraphTimeoutException(String subgraphName, Duration timeout) {
        super("Subgraph '" + subgraphName + "' timed out after " + timeout.toMillis() + "ms");
        this.subgraphName = subgraphName;
        this.timeout = timeout;
    }

    public SubgraphTimeoutException(String subgraphName, Duration timeout, Throwable cause) {
        super("Subgraph '" + subgraphName + "' timed out after " + timeout.toMillis() + "ms", cause);
        this.subgraphName = subgraphName;
        this.timeout = timeout;
    }

    public String subgraphName() {
        return subgraphName;
    }

    public Duration timeout() {
        return timeout;
    }
}
