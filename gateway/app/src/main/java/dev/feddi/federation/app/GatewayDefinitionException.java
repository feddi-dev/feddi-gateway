package dev.feddi.federation.app;

/**
 * Exception thrown when a gateway definition is invalid or cannot be loaded.
 */
public class GatewayDefinitionException extends RuntimeException {
    public GatewayDefinitionException(String message) {
        super(message);
    }

    public GatewayDefinitionException(String message, Throwable cause) {
        super(message, cause);
    }
}
