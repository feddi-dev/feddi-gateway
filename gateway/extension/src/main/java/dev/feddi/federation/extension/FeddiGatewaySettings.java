package dev.feddi.federation.extension;

import java.time.Duration;

/**
 * Gateway-level settings.
 *
 * @param timeoutMs timeout in milliseconds for subgraph calls (null = use default)
 * @param maxResponseBytes the largest subgraph response body read, in bytes; a subgraph's own
 *                         {@code maxResponseBytes} overrides it (null = use default)
 */
public record FeddiGatewaySettings(Long timeoutMs, Long maxResponseBytes) {
    public static final long DEFAULT_TIMEOUT_MS = 30000;
    public static final long DEFAULT_MAX_RESPONSE_BYTES = 8L * 1024 * 1024;

    /**
     * Creates settings with a timeout and the default response limit.
     */
    public FeddiGatewaySettings(Long timeoutMs) {
        this(timeoutMs, null);
    }

    /**
     * Creates settings with default values.
     */
    public static FeddiGatewaySettings defaults() {
        return new FeddiGatewaySettings(DEFAULT_TIMEOUT_MS, DEFAULT_MAX_RESPONSE_BYTES);
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
}
