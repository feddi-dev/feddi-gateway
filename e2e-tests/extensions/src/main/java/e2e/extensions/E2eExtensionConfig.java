package e2e.extensions;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Configuration for the e2e test extension.
 * Deserialized from the {@code extensions.e2e} section of feddi-gateway.yml.
 */
public class E2eExtensionConfig {

    @JsonProperty("timeout-seconds")
    private int timeoutSeconds = 10;

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
