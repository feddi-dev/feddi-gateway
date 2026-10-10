package dev.feddi.federation.app;

import dev.feddi.federation.engine.compose.Composer.SubgraphInput;
import dev.feddi.federation.extension.SubgraphClient;
import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.language.AstPrinter;
import graphql.language.Document;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Response objects must list their fields in the order of the query's selection set, even when
 * the fields come from different subgraphs (GraphQL spec, "Serialization Format": the serialized
 * map should preserve the order of the selection set).
 */
class ResponseFieldOrderTest {

    private static final String PRODUCTS_SDL = """
        type Query {
          products: [Product]
          productById(id: ID!): Product @lookup @shareable
        }
        type Product @key(fields: "id") {
          id: ID!
          name: String
          price: Int
        }
        """;

    private static final String INVENTORY_SDL = """
        type Query {
          productById(id: ID!): Product @lookup @shareable
        }
        type Product @key(fields: "id") {
          id: ID!
          inStock: Boolean
          warehouse: Warehouse
        }
        type Warehouse {
          city: String
          code: String
        }
        """;

    private static final List<Map<String, Object>> PRODUCTS = List.of(
        product("1", "Table", 899, true),
        product("2", "Chair", 129, false));

    @Test
    void fieldsFromDifferentSubgraphsFollowTheQueryOrder() {
        var data = execute("{ products { inStock name id warehouse { code city } price } }");

        List<Map<String, Object>> products = list(data.get("products"));
        assertThat(products).hasSize(2).allSatisfy(product -> {
            assertThat(product.keySet()).containsExactly("inStock", "name", "id", "warehouse", "price");
            assertThat(map(product.get("warehouse")).keySet()).containsExactly("code", "city");
        });
    }

    @Test
    void aliasesFollowTheQueryOrder() {
        var data = execute("{ products { available: inStock label: name id } }");

        assertThat(list(data.get("products"))).allSatisfy(product ->
            assertThat(product.keySet()).containsExactly("available", "label", "id"));
    }

    @Test
    void rootFieldsFollowTheQueryOrder() {
        var data = execute("{ second: products { id } first: products { inStock } }");

        assertThat(data.keySet()).containsExactly("second", "first");
    }

    @Test
    void cachedPlansKeepTheQueryOrder() {
        var gateway = gateway();
        String query = "{ products { inStock name id } }";

        execute(gateway, query);
        var data = execute(gateway, query);

        assertThat(list(data.get("products"))).allSatisfy(product ->
            assertThat(product.keySet()).containsExactly("inStock", "name", "id"));
    }

    private static FeddiFederationGateway gateway() {
        return FeddiFederationGateway.create(
            List.of(new SubgraphInput("products", "http://products", PRODUCTS_SDL),
                new SubgraphInput("inventory", "http://inventory", INVENTORY_SDL)),
            Map.of("products", client(PRODUCTS_SDL, "products"), "inventory", client(INVENTORY_SDL, "inventory")));
    }

    private static Map<String, Object> execute(String query) {
        return execute(gateway(), query);
    }

    private static Map<String, Object> execute(FeddiFederationGateway gateway, String query) {
        var result = gateway.execute(ExecutionInput.newExecutionInput().query(query).build()).block().executionResult();
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }

    /** Executes subgraph operations against an executable copy of the subgraph schema. */
    private static SubgraphClient client(String sdl, String subgraph) {
        String plainSdl = sdl.replace(" @lookup", "").replace(" @shareable", "").replace(" @key(fields: \"id\")", "");
        var wiring = RuntimeWiring.newRuntimeWiring()
            .type("Query", t -> t
                .dataFetcher("products", env -> copies(PRODUCTS))
                .dataFetcher("productById", env -> PRODUCTS.stream()
                    .filter(p -> p.get("id").equals(env.getArgument("id"))).findFirst()
                    .map(LinkedHashMap::new).orElse(null)))
            .build();
        var graphQL = GraphQL.newGraphQL(new SchemaGenerator().makeExecutableSchema(new SchemaParser().parse(plainSdl), wiring)).build();
        return (PerEntitySubgraphClient) (operation, variables, context) -> Mono.fromCallable(() -> graphQL.execute(ExecutionInput.newExecutionInput()
            .query(AstPrinter.printAst(Document.newDocument().definition(operation).build()))
            .variables(variables)
            .build()));
    }

    private static Map<String, Object> product(String id, String name, int price, boolean inStock) {
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("id", id);
        product.put("name", name);
        product.put("price", price);
        product.put("inStock", inStock);
        product.put("warehouse", new LinkedHashMap<>(Map.of("city", "Berlin", "code", "BER")));
        return product;
    }

    private static List<Map<String, Object>> copies(List<Map<String, Object>> rows) {
        List<Map<String, Object>> copies = new ArrayList<>();
        rows.forEach(row -> copies.add(new LinkedHashMap<>(row)));
        return copies;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
