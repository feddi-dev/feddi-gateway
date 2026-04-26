package dev.feddi.federation.app;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe holder for the FederationGateway.
 *
 * Enables atomic hot-reload of the gateway - in-flight requests complete
 * with the old gateway while new requests get the new gateway.
 */
@Component
public class GatewayHolder {

    private final AtomicReference<FederationGateway> gatewayRef = new AtomicReference<>();

    /**
     * Gets the current gateway, or null if not initialized.
     */
    public FederationGateway get() {
        return gatewayRef.get();
    }

    /**
     * Atomically sets a new gateway.
     */
    public void set(FederationGateway gateway) {
        gatewayRef.set(gateway);
    }

    /**
     * Returns true if a gateway has been configured.
     */
    public boolean isInitialized() {
        return gatewayRef.get() != null;
    }
}
