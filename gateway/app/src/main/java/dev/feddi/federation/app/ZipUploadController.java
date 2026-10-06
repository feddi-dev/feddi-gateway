package dev.feddi.federation.app;

import dev.feddi.federation.extension.FeddiGatewayDefinitionSource;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Handles schema upload requests. Not a Spring controller — served by {@link AdminServer}
 * on a dedicated admin port bound to localhost.
 *
 * <p>Only created when no extension-provided {@link FeddiGatewayDefinitionSource}
 * is registered (see {@link FeddiGatewayDefinitionSourceConfiguration}).
 */
public class ZipUploadController {

    /**
     * HTTP status and JSON body of an upload response.
     */
    public record UploadResponse(int status, Map<String, Object> body) {
    }

    private final ZipUploadService uploadService;

    public ZipUploadController(ZipUploadService uploadService) {
        this.uploadService = uploadService;
    }

    /**
     * Process a ZIP file upload containing subgraph configurations. Completes when the new
     * configuration is active or has been rejected.
     *
     * @param zipBytes the raw ZIP bytes
     * @return 200 when the configuration is active, 400 when it was rejected (the active
     *         configuration is kept), 409 while another upload is being activated
     */
    public Mono<UploadResponse> handleUpload(byte[] zipBytes) {
        return uploadService.processZip(zipBytes)
            .thenReturn(new UploadResponse(200, Map.of(
                "success", true,
                "message", "Gateway configuration updated successfully")))
            .onErrorResume(ZipUploadService.UploadInProgressException.class,
                e -> Mono.just(new UploadResponse(409, Map.of("success", false, "error", e.getMessage()))))
            .onErrorResume(FeddiGatewayDefinitionException.class,
                e -> Mono.just(new UploadResponse(400, Map.of("success", false, "error", e.getMessage()))))
            .onErrorResume(FeddiFederationGateway.CompositionException.class,
                e -> Mono.just(new UploadResponse(400, Map.of("success", false,
                    "error", "Schema composition failed: " + e.getMessage()))));
    }
}
