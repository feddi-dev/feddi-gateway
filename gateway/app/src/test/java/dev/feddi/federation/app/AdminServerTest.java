package dev.feddi.federation.app;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminServerTest {

    @Test
    void lifecycleAndUploadResponses() {
        AdminServer server = new AdminServer(stubUploadController(), adminConfig());

        assertFalse(server.isRunning());
        assertThrows(IllegalStateException.class, server::getPort);
        server.stop();

        try {
            server.start();

            assertTrue(server.isRunning());

            WebClient client = WebClient.builder()
                    .baseUrl("http://127.0.0.1:" + server.getPort())
                    .build();

            assertEquals(HttpStatus.OK, upload(client, "ok"));
            assertEquals(HttpStatus.BAD_REQUEST, upload(client, "bad"));
        } finally {
            server.stop();
        }

        assertFalse(server.isRunning());
    }

    private HttpStatus upload(WebClient client, String body) {
        return client.post()
                .uri("/admin/upload")
                .bodyValue(body.getBytes(StandardCharsets.UTF_8))
                .exchangeToMono(response -> response.releaseBody()
                        .thenReturn(HttpStatus.valueOf(response.statusCode().value())))
                .block();
    }

    private ZipUploadController stubUploadController() {
        return new ZipUploadController(null) {
            @Override
            public Map<String, Object> handleUpload(byte[] zipBytes) {
                String body = new String(zipBytes, StandardCharsets.UTF_8);
                if ("ok".equals(body)) {
                    return Map.of("success", true, "message", "uploaded");
                }
                return Map.of("success", false, "error", "invalid zip");
            }
        };
    }

    private FeddiGatewayConfigFile adminConfig() {
        FeddiGatewayConfigFile config = new FeddiGatewayConfigFile();
        config.setAdminPort(0);
        config.setAdminAddress("127.0.0.1");
        return config;
    }
}
