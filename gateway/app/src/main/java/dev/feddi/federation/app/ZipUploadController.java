package dev.feddi.federation.app;

import java.util.Map;

/**
 * Handles schema upload requests. Not a Spring controller — served by {@link AdminServer}
 * on a dedicated admin port bound to localhost.
 *
 * <p>Only created when no custom {@link dev.feddi.federation.customization.GatewayDefinitionSource}
 * is registered (see {@link GatewayDefinitionSourceConfiguration}).
 */
public class ZipUploadController {

    private final ZipUploadService uploadService;

    public ZipUploadController(ZipUploadService uploadService) {
        this.uploadService = uploadService;
    }

    /**
     * Process a ZIP file upload containing subgraph configurations.
     *
     * @param zipBytes the raw ZIP bytes
     * @return response map with success/error
     */
    public Map<String, Object> handleUpload(byte[] zipBytes) {
        try {
            uploadService.processZip(zipBytes);
            return Map.of(
                "success", true,
                "message", "Gateway configuration updated successfully"
            );
        } catch (GatewayDefinitionException e) {
            return Map.of("success", false, "error", e.getMessage());
        } catch (FederationGateway.CompositionException e) {
            return Map.of("success", false, "error", "Schema composition failed: " + e.getMessage());
        }
    }
}
