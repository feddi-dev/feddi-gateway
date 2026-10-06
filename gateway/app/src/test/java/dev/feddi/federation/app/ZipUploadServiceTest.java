package dev.feddi.federation.app;

import dev.feddi.federation.extension.FeddiGatewayDefinition;
import dev.feddi.federation.extension.SubgraphClientFactory;
import graphql.ExecutionInput;
import graphql.ExecutionResultImpl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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

        ZipUploadService service = new ZipUploadService(source, reloadService(Duration.ZERO));

        assertDoesNotThrow(() -> service.processZip(zipBytes).block());

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

    @Test
    void uploadCompletesOnlyWhenTheConfigurationIsActive() throws IOException {
        ZipUploadService service = new ZipUploadService(source, reloadService(Duration.ofMillis(200)));

        service.processZip(zip(MAIN_CONFIG)).block();

        assertTrue(holder.isInitialized());
        assertTrue(source.load().isPresent());
    }

    @Test
    void rejectedUploadReportsTheErrorAndKeepsTheActiveConfiguration() throws IOException {
        var controller = new ZipUploadController(new ZipUploadService(source, reloadService(Duration.ZERO)));
        controller.handleUpload(zip(MAIN_CONFIG)).block();
        var activeGateway = holder.get();
        var activeDefinition = source.load().orElseThrow();

        var response = controller.handleUpload(zip(MAIN_CONFIG + "\nbatching: sometimes")).block();

        assertEquals(400, response.status());
        assertEquals(false, response.body().get("success"));
        assertTrue(response.body().get("error").toString().contains("batching"));
        assertSame(activeGateway, holder.get());
        assertSame(activeDefinition, source.load().orElseThrow());
    }

    @Test
    void uploadWhileAnotherIsBeingActivatedIsAConflict() throws IOException {
        var controller = new ZipUploadController(new ZipUploadService(source, reloadService(Duration.ofMillis(300))));
        var first = controller.handleUpload(zip(MAIN_CONFIG)).toFuture();

        var second = controller.handleUpload(zip(MAIN_CONFIG)).block();

        assertEquals(409, second.status());
        assertEquals(200, first.join().status());
        assertEquals(200, controller.handleUpload(zip(MAIN_CONFIG)).block().status());
    }

    private static final String MAIN_SCHEMA = """
        type Query {
          products: [Product]
        }

        type Product {
          id: ID!
          name: String
        }
        """;

    private static final String MAIN_CONFIG = "url: http://localhost:4001/";

    private final FeddiGatewayHolder holder = new FeddiGatewayHolder();
    private final DefaultFeddiGatewayDefinitionSource source = new DefaultFeddiGatewayDefinitionSource();

    private byte[] zip(String config) throws IOException {
        return createZip("subgraphs/main/schema.graphqls", MAIN_SCHEMA, "subgraphs/main/config.yaml", config);
    }

    /**
     * A reload service whose reloads take at least {@code delay}, like one waiting for a
     * variable batching check.
     */
    private FeddiGatewayReloadService reloadService(Duration delay) {
        SubgraphClientFactory factory = (subgraphName, config) -> (PerEntitySubgraphClient) (op, vars, ctx) ->
            Mono.just(ExecutionResultImpl.newExecutionResult()
                .data(Map.of("products", List.of(Map.of("id", "1", "name", "Test Product"))))
                .build());
        return new FeddiGatewayReloadService(holder, factory, new FeddiGatewayMetrics(new SimpleMeterRegistry()),
            null, new FeddiGatewayConfigFile()) {
            @Override
            public Mono<Void> reload(FeddiGatewayDefinition gatewayDefinition) {
                return Mono.delay(delay).then(super.reload(gatewayDefinition));
            }
        };
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
