package dev.feddi.federation.extension;

import java.time.Duration;

/**
 * Gateway-level settings.
 *
 * @param timeoutMs timeout in milliseconds for subgraph calls (null = use default)
 */
public record FeddiGatewaySettings(Long timeoutMs) {
    public static final long DEFAULT_TIMEOUT_MS = 30000;

    /**
     * Creates settings with default values.
     */
    public static FeddiGatewaySettings defaults() {
        return new FeddiGatewaySettings(DEFAULT_TIMEOUT_MS);
    }

    /**
     * Gets the timeout as a Duration.
     */
    public Duration timeout() {
        return Duration.ofMillis(timeoutMs != null ? timeoutMs : DEFAULT_TIMEOUT_MS);
    }
}
