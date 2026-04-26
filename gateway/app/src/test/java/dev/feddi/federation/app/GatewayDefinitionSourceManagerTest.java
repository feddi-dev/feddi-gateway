package dev.feddi.federation.app;

import dev.feddi.federation.customization.GatewayDefinition;
import dev.feddi.federation.customization.GatewaySettings;
import dev.feddi.federation.customization.SubgraphClientFactory;
import dev.feddi.federation.customization.SubgraphDefinition;
import dev.feddi.federation.customization.SubgraphSettings;
import graphql.ExecutionInput;
import graphql.ExecutionResultImpl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayDefinitionSourceManagerTest {

    @Test
    void initializesGatewayFromSourceAtStartup() {
        DefaultGatewayDefinitionSource source = new DefaultGatewayDefinitionSource();
        source.replace(gatewayDefinition("startup"));

        GatewayHolder holder = new GatewayHolder();
        GatewayReloadService reloadService = new GatewayReloadService(holder, subgraphClientFactory(), new GatewayMetrics(new SimpleMeterRegistry()), null, new GatewayConfigFile());
        GatewayDefinitionSourceManager manager = new GatewayDefinitionSourceManager(source, reloadService);

        manager.run(new DefaultApplicationArguments(new String[0]));

        assertTrue(holder.isInitialized());
        assertQueryResult(holder, "Loaded at startup");
    }

    @Test
    void refreshesGatewayWhenSourcePublishesUpdate() {
        DefaultGatewayDefinitionSource source = new DefaultGatewayDefinitionSource();

        GatewayHolder holder = new GatewayHolder();
        GatewayReloadService reloadService = new GatewayReloadService(holder, subgraphClientFactory(), new GatewayMetrics(new SimpleMeterRegistry()), null, new GatewayConfigFile());
        GatewayDefinitionSourceManager manager = new GatewayDefinitionSourceManager(source, reloadService);

        manager.run(new DefaultApplicationArguments(new String[0]));
        source.replace(gatewayDefinition("update"));

        assertTrue(holder.isInitialized());
        assertQueryResult(holder, "Loaded from update");
    }

    @Test
    void preComposedSupergraphWithCustomScalarsInitializesAndExecutesQueries() {
        String subgraphSdl = """
                scalar JSON
                scalar Long

                type Query {
                  attractions: [AttractionPoint]
                }

                type AttractionPoint @key(fields: "id") {
                  id: Int!
                  name: String!
                  categories: JSON
                  openingHours: JSON
                }
                """;

        String supergraphSdl = """
                scalar JSON
                scalar Long

                type Query {
                  attractions: [AttractionPoint]
                }

                type AttractionPoint {
                  id: Int!
                  name: String!
                  categories: JSON
                  openingHours: JSON
                }
                """;

        // Mock subgraph client returns custom scalar values (JSON maps/lists)
        var categories = List.of("museum", "historical");
        var openingHours = Map.of("mon", "09:00-17:00", "tue", "09:00-17:00");

        SubgraphClientFactory factory = (subgraphName, settings) ->
                (operation, variables, context) -> reactor.core.publisher.Mono.just(
                        ExecutionResultImpl.newExecutionResult()
                                .data(Map.of("attractions", List.of(Map.of(
                                        "id", 1,
                                        "name", "Ancient Temple",
                                        "categories", categories,
                                        "openingHours", openingHours
                                ))))
                                .build()
                );

        DefaultGatewayDefinitionSource source = new DefaultGatewayDefinitionSource();
        source.replace(new GatewayDefinition(
                Map.of(
                        "attractions", new SubgraphDefinition(
                                subgraphSdl,
                                new SubgraphSettings(Map.of("url", "http://attractions.local/graphql"))
                        )
                ),
                GatewaySettings.defaults(),
                supergraphSdl
        ));

        GatewayHolder holder = new GatewayHolder();
        GatewayReloadService reloadService = new GatewayReloadService(
                holder, factory, new GatewayMetrics(new SimpleMeterRegistry()), null, new GatewayConfigFile());
        GatewayDefinitionSourceManager manager = new GatewayDefinitionSourceManager(source, reloadService);

        manager.run(new DefaultApplicationArguments(new String[0]));

        assertTrue(holder.isInitialized());

        // Execute a query that returns custom scalar fields
        var gatewayResult = holder.get().execute(
                ExecutionInput.newExecutionInput()
                        .query("{ attractions { id name categories openingHours } }")
                        .build()
        ).block();

        assertNotNull(gatewayResult);
        var spec = gatewayResult.executionResult().toSpecification();

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) spec.get("data");
        assertNotNull(data, "Response should have data: " + spec);

        @SuppressWarnings("unchecked")
        var attractions = (List<Map<String, Object>>) data.get("attractions");
        assertEquals(1, attractions.size());

        var attraction = attractions.get(0);
        assertEquals(1, attraction.get("id"));
        assertEquals("Ancient Temple", attraction.get("name"));
        assertEquals(categories, attraction.get("categories"));
        assertEquals(openingHours, attraction.get("openingHours"));
    }

    private GatewayDefinition gatewayDefinition(String key) {
        return new GatewayDefinition(
            Map.of(
                "catalog", new SubgraphDefinition(
                    """
                        type Query {
                          products: [Product]
                        }

                        type Product {
                          id: ID!
                          name: String
                        }
                        """,
                    new SubgraphSettings(Map.of("url", "http://catalog.local/graphql/" + key))
                )
            ),
            GatewaySettings.defaults()
        );
    }

    private SubgraphClientFactory subgraphClientFactory() {
        return (subgraphName, settings) -> (operation, variables, context) -> reactor.core.publisher.Mono.just(
            ExecutionResultImpl.newExecutionResult()
                .data(Map.of("products", List.of(Map.of("id", "1", "name", extractExpectedName(settings.config().get("url").toString())))))
                .build()
        );
    }

    private String extractExpectedName(String url) {
        return url.endsWith("/update") ? "Loaded from update" : "Loaded at startup";
    }

    @Test
    void introspectionDisabledReturnsError() {
        DefaultGatewayDefinitionSource source = new DefaultGatewayDefinitionSource();
        source.replace(gatewayDefinition("introspection-test"));

        GatewayConfigFile config = new GatewayConfigFile();
        config.setEnableIntrospection(false);

        GatewayHolder holder = new GatewayHolder();
        GatewayReloadService reloadService = new GatewayReloadService(
                holder, subgraphClientFactory(), new GatewayMetrics(new SimpleMeterRegistry()), null, config);
        GatewayDefinitionSourceManager manager = new GatewayDefinitionSourceManager(source, reloadService);
        manager.run(new DefaultApplicationArguments(new String[0]));

        assertTrue(holder.isInitialized());

        // Introspection query should fail
        var gatewayResult = holder.get().execute(
                ExecutionInput.newExecutionInput()
                        .query("{ __schema { types { name } } }")
                        .build()
        ).block();

        assertNotNull(gatewayResult);
        var spec = gatewayResult.executionResult().toSpecification();
        @SuppressWarnings("unchecked")
        var errors = (List<Map<String, Object>>) spec.get("errors");
        assertNotNull(errors, "Introspection should return errors when disabled: " + spec);
        assertTrue(errors.stream().anyMatch(e ->
                        e.get("message").toString().contains("Introspection")),
                "Error should mention introspection: " + errors);
    }

    @Test
    void introspectionEnabledByDefaultReturnsSchema() {
        DefaultGatewayDefinitionSource source = new DefaultGatewayDefinitionSource();
        source.replace(gatewayDefinition("introspection-enabled"));

        GatewayHolder holder = new GatewayHolder();
        GatewayReloadService reloadService = new GatewayReloadService(
                holder, subgraphClientFactory(), new GatewayMetrics(new SimpleMeterRegistry()), null, new GatewayConfigFile());
        GatewayDefinitionSourceManager manager = new GatewayDefinitionSourceManager(source, reloadService);
        manager.run(new DefaultApplicationArguments(new String[0]));

        assertTrue(holder.isInitialized());

        // Introspection query should succeed
        var gatewayResult = holder.get().execute(
                ExecutionInput.newExecutionInput()
                        .query("{ __schema { types { name } } }")
                        .build()
        ).block();

        assertNotNull(gatewayResult);
        var spec = gatewayResult.executionResult().toSpecification();
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) spec.get("data");
        assertNotNull(data, "Introspection should return data when enabled: " + spec);
        assertNotNull(data.get("__schema"), "Should have __schema: " + spec);
    }

    private void assertQueryResult(GatewayHolder holder, String expectedName) {
        var gatewayResult = holder.get().execute(
            ExecutionInput.newExecutionInput().query("{ products { id name } }").build()
        ).block();

        assertNotNull(gatewayResult);
        var result = gatewayResult.executionResult();
        assertEquals(Map.of(
            "data", Map.of("products", List.of(Map.of("id", "1", "name", expectedName)))
        ), result.toSpecification());
    }
}
