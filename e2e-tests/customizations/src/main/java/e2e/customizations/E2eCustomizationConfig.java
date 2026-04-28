package e2e.customizations;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Configuration for the e2e test customization.
 * Deserialized from the {@code extensions.e2e} section of feddi-gateway.yml.
 */
public class E2eCustomizationConfig {

    @JsonProperty("timeout-seconds")
    private int timeoutSeconds = 10;

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
