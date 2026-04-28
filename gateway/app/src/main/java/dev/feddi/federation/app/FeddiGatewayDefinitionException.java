package dev.feddi.federation.app;

/**
 * Exception thrown when a gateway definition is invalid or cannot be loaded.
 */
public class FeddiGatewayDefinitionException extends RuntimeException {
    public FeddiGatewayDefinitionException(String message) {
        super(message);
    }

    public FeddiGatewayDefinitionException(String message, Throwable cause) {
        super(message, cause);
    }
}
