package dev.feddi.federation.app;

import dev.feddi.federation.customization.SubgraphClientFactory;
import graphql.ExecutionInput;
import graphql.ExecutionResultImpl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZipUploadServiceTest {

    @Test
    void testBasicSchemaComposition() throws IOException {
        String mainSchema = """
            type Query {
              products: [Product]
              productById(id: ID!): Product
            }

            type Product {
              id: ID!
              name: String
              price: Int
            }
            """;

        String mainConfig = "url: http://localhost:4001/";

        byte[] zipBytes = createZip(
            "subgraphs/main/schema.graphqls", mainSchema,
            "subgraphs/main/config.yaml", mainConfig
        );

        GatewayHolder holder = new GatewayHolder();
        SubgraphClientFactory factory = (subgraphName, config) -> (op, vars, ctx) ->
            reactor.core.publisher.Mono.just(ExecutionResultImpl.newExecutionResult()
                .data(Map.of("products", List.of(
                    Map.of("id", "1", "name", "Test Product")
                )))
                .build());

        DefaultGatewayDefinitionSource source = new DefaultGatewayDefinitionSource();
        GatewayReloadService reloadService = new GatewayReloadService(holder, factory, new GatewayMetrics(new SimpleMeterRegistry()), null, new GatewayConfigFile());
        GatewayDefinitionSourceManager manager = new GatewayDefinitionSourceManager(source, reloadService);
        manager.run(new DefaultApplicationArguments(new String[0]));
        ZipUploadService service = new ZipUploadService(source);

        assertDoesNotThrow(() -> service.processZip(zipBytes));

        assertTrue(holder.isInitialized());
        assertNotNull(holder.get());

        ExecutionInput executionInput = ExecutionInput.newExecutionInput()
            .query("{ products { id name } }")
            .build();

        var gatewayResult = holder.get().execute(executionInput).block();
        assertNotNull(gatewayResult);
        var result = gatewayResult.executionResult();
        assertEquals(Map.of(
            "data", Map.of("products", List.of(Map.of("id", "1", "name", "Test Product")))
        ), result.toSpecification());
    }

    private byte[] createZip(String... pathsAndContents) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (int i = 0; i < pathsAndContents.length; i += 2) {
                String path = pathsAndContents[i];
                String content = pathsAndContents[i + 1];
                zos.putNextEntry(new ZipEntry(path));
                zos.write(content.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }
}
