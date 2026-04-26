package dev.feddi.federation.customization;

import java.time.Duration;

/**
 * Gateway-level settings.
 *
 * @param timeoutMs timeout in milliseconds for subgraph calls (null = use default)
 */
public record GatewaySettings(Long timeoutMs) {
    public static final long DEFAULT_TIMEOUT_MS = 30000;

    /**
     * Creates settings with default values.
     */
    public static GatewaySettings defaults() {
        return new GatewaySettings(DEFAULT_TIMEOUT_MS);
    }

    /**
     * Gets the timeout as a Duration.
     */
    public Duration timeout() {
        return Duration.ofMillis(timeoutMs != null ? timeoutMs : DEFAULT_TIMEOUT_MS);
    }
}
