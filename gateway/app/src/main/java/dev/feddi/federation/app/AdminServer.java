package dev.feddi.federation.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.feddi.federation.extension.FeddiGatewayDefinitionSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import static org.springframework.web.reactive.function.server.RequestPredicates.POST;

/**
 * Separate HTTP server for admin endpoints (schema upload), bound to localhost only.
 * Runs on a dedicated port (default 9091) to isolate admin operations from public traffic.
 *
 * <p>Only created when no extension-provided {@link FeddiGatewayDefinitionSource}
 * is registered (see {@link FeddiGatewayDefinitionSourceConfiguration}).
 */
public class AdminServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AdminServer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ZipUploadController uploadController;
    private final int port;
    private final String host;
    private DisposableServer server;

    public AdminServer(ZipUploadController uploadController, FeddiGatewayConfigFile config) {
        this.uploadController = uploadController;
        this.port = config.getAdminPort();
        this.host = config.getAdminAddress();
    }

    @Override
    public void start() {
        RouterFunction<ServerResponse> route = RouterFunctions
                .route(POST("/admin/upload"), this::handleUpload);

        var httpHandler = RouterFunctions.toHttpHandler(route);
        var adapter = new ReactorHttpHandlerAdapter(httpHandler);

        server = HttpServer.create()
                .host(host)
                .port(port)
                .handle(adapter)
                .bindNow();

        log.info("Admin server started on {}:{}", host, server.port());
    }

    @Override
    public void stop() {
        if (server != null) {
            server.disposeNow();
            log.info("Admin server stopped");
        }
    }

    @Override
    public boolean isRunning() {
        return server != null && !server.isDisposed();
    }

    /**
     * Returns the port the admin server is listening on (useful for tests with port 0).
     */
    public int getPort() {
        if (server == null) {
            throw new IllegalStateException("Admin server not started");
        }
        return server.port();
    }

    private Mono<ServerResponse> handleUpload(ServerRequest request) {
        return request.bodyToMono(byte[].class)
                .flatMap(body -> {
                    var result = uploadController.handleUpload(body);
                    boolean success = Boolean.TRUE.equals(result.get("success"));
                    var status = success ? HttpStatus.OK : HttpStatus.BAD_REQUEST;
                    try {
                        String json = MAPPER.writeValueAsString(result);
                        return ServerResponse.status(status)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(json);
                    } catch (Exception e) {
                        return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                .bodyValue("{\"success\":false,\"error\":\"Serialization failed\"}");
                    }
                })
                .onErrorResume(e -> {
                    log.error("Admin upload failed", e);
                    return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue("{\"success\":false,\"error\":\"" + e.getMessage() + "\"}");
                });
    }
}
