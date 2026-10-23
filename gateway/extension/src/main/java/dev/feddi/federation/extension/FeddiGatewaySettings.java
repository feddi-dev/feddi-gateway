package dev.feddi.federation.extension;

import java.time.Duration;

/**
 * Gateway-level settings.
 *
 * @param timeoutMs timeout in milliseconds for subgraph calls (null = use default)
 * @param maxResponseBytes the largest subgraph response body read, in bytes; a subgraph's own
 *                         {@code maxResponseBytes} overrides it (null = use default)
 * @param connectTimeoutMs how long to wait for a connection to a subgraph, in milliseconds (null = use default)
 * @param maxIdleTimeMs how long a connection to a subgraph may stay unused before it is closed, in milliseconds;
 *                      below the subgraph's (or its load balancer's) idle timeout, so that the gateway closes it
 *                      first (null = use default)
 * @param maxConnections the most connections to one subgraph address (null = use default)
 */
public record FeddiGatewaySettings(Long timeoutMs, Long maxResponseBytes, Long connectTimeoutMs,
                                   Long maxIdleTimeMs, Integer maxConnections) {
    public static final long DEFAULT_TIMEOUT_MS = 30000;
    public static final long DEFAULT_MAX_RESPONSE_BYTES = 8L * 1024 * 1024;
    public static final long DEFAULT_CONNECT_TIMEOUT_MS = 5000;
    /** Below the shortest common idle timeouts (Node.js closes idle connections after 5 seconds). */
    public static final long DEFAULT_MAX_IDLE_TIME_MS = 4000;
    /**
     * Enough for a query's concurrent lookups under load: a call holds its connection until the subgraph answers,
     * and Reactor Netty's default (twice the cores, at least 16) makes requests queue for a connection.
     */
    public static final int DEFAULT_MAX_CONNECTIONS = 256;

    /**
     * Creates settings with a timeout and defaults for everything else.
     */
    public FeddiGatewaySettings(Long timeoutMs) {
        this(timeoutMs, null, null, null, null);
    }

    /**
     * Creates settings with default values.
     */
    public static FeddiGatewaySettings defaults() {
        return new FeddiGatewaySettings(DEFAULT_TIMEOUT_MS, DEFAULT_MAX_RESPONSE_BYTES, DEFAULT_CONNECT_TIMEOUT_MS,
            DEFAULT_MAX_IDLE_TIME_MS, null);
    }

    /**
     * Gets the timeout as a Duration.
     */
    public Duration timeout() {
        return Duration.ofMillis(timeoutMs != null ? timeoutMs : DEFAULT_TIMEOUT_MS);
    }

    /**
     * Gets the response limit in bytes, the default if none is set.
     */
    public long responseLimit() {
        return maxResponseBytes != null ? maxResponseBytes : DEFAULT_MAX_RESPONSE_BYTES;
    }

    /**
     * Gets the connect timeout, the default if none is set.
     */
    public Duration connectTimeout() {
        return Duration.ofMillis(connectTimeoutMs != null ? connectTimeoutMs : DEFAULT_CONNECT_TIMEOUT_MS);
    }

    /**
     * Gets the idle time after which unused connections are closed, the default if none is set.
     */
    public Duration maxIdleTime() {
        return Duration.ofMillis(maxIdleTimeMs != null ? maxIdleTimeMs : DEFAULT_MAX_IDLE_TIME_MS);
    }
}
