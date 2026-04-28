package dev.feddi.federation.app;

/**
 * Exception thrown when a feddi Gateway definition is invalid or cannot be loaded.
 */
public class FeddiGatewayDefinitionException extends RuntimeException {
    public FeddiGatewayDefinitionException(String message) {
        super(message);
    }

    public FeddiGatewayDefinitionException(String message, Throwable cause) {
        super(message, cause);
    }
}
