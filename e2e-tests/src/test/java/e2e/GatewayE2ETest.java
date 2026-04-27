package e2e;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * True end-to-end tests using Docker Compose with real subgraphs.
 *
 * <p>This test is completely independent from the gateway codebase.
 * It only depends on:
 * <ul>
 *   <li>The gateway Docker image (built from the Spring Boot JAR)</li>
 *   <li>Node.js subgraph implementations in docker/subgraphs/</li>
 * </ul>
 *
 * <p>Tests federation across two subgraphs:
 * <ul>
 *   <li>products: owns Product entity with name, price</li>
 *   <li>reviews: extends Product with reviews field</li>
 * </ul>
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class GatewayE2ETest {

    private static File getDockerComposeFile() {
        Path projectRoot = Path.of(System.getProperty("user.dir"));
        return projectRoot.resolve("docker/docker-compose.yml").toFile();
    }

    @Container
    private static final ComposeContainer environment = new ComposeContainer(
            getDockerComposeFile())
        .withExposedService("feddi-gateway-1", 8080, Wait.forListeningPort()
            .withStartupTimeout(Duration.ofMinutes(3)))
        .withExposedService("feddi-gateway-1", 9091)
        .withExposedService("products-1", 4001, Wait.forListeningPort()
            .withStartupTimeout(Duration.ofMinutes(2)))
        .withExposedService("reviews-1", 4002, Wait.forListeningPort()
            .withStartupTimeout(Duration.ofMinutes(2)));

    private WebClient gatewayClient;
    private WebClient adminClient;

    @BeforeAll
    void setup() {
        String gatewayHost = environment.getServiceHost("feddi-gateway-1", 8080);
        int gatewayPort = environment.getServicePort("feddi-gateway-1", 8080);
        int adminPort = environment.getServicePort("feddi-gateway-1", 9091);

        gatewayClient = WebClient.builder()
            .baseUrl("http://" + gatewayHost + ":" + gatewayPort)
            .build();
        adminClient = WebClient.builder()
            .baseUrl("http://" + gatewayHost + ":" + adminPort)
            .build();
    }

    @Test
    @Order(1)
    void gatewayReturns503WhenNotInitialized() {
        try {
            gatewayClient.post()
                .uri("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", "{ products { id } }"))
                .retrieve()
                .bodyToMono(Map.class)
                .block();
            fail("Should have thrown an error");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("503"));
        }
    }

    @Test
    @Order(2)
    void uploadSubgraphConfigAndQueryProducts() throws IOException {
        byte[] zipBytes = createSubgraphZip();

        @SuppressWarnings("unchecked")
        Map<String, Object> uploadResponse = adminClient.post()
            .uri("/admin/upload")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .bodyValue(zipBytes)
            .exchangeToMono(r -> {
                if (r.statusCode().isError()) {
                    return r.bodyToMono(String.class)
                        .map(body -> {
                            throw new RuntimeException("Upload failed with status " + r.statusCode() + ": " + body);
                        });
                }
                return r.bodyToMono(Map.class);
            })
            .block();

        assertNotNull(uploadResponse);
        assertEquals(true, uploadResponse.get("success"));

        // Query products only (from products subgraph)
        @SuppressWarnings("unchecked")
        Map<String, Object> response = executeGraphQL("{ products { id name price } }");

        assertNotNull(response);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertNotNull(data);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> products = (List<Map<String, Object>>) data.get("products");
        assertNotNull(products);
        assertFalse(products.isEmpty());

        Map<String, Object> firstProduct = products.get(0);
        assertEquals("1", firstProduct.get("id"));
        assertEquals("Table", firstProduct.get("name"));
        assertEquals(899, firstProduct.get("price"));
    }

    @Test
    @Order(3)
    void queryReviewsFromReviewsSubgraph() {
        // Query reviews only (from reviews subgraph) - tests unified root
        @SuppressWarnings("unchecked")
        Map<String, Object> response = executeGraphQL("{ reviews { id text stars } }");

        assertNotNull(response);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertNotNull(data);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reviews = (List<Map<String, Object>>) data.get("reviews");
        assertNotNull(reviews);
        assertFalse(reviews.isEmpty());

        Map<String, Object> firstReview = reviews.get(0);
        assertEquals("101", firstReview.get("id"));
        assertEquals("Great table, very sturdy!", firstReview.get("text"));
        assertEquals(5, firstReview.get("stars"));
    }

    @Test
    @Order(4)
    void queryProductsWithReviews_crossSubgraphLookup() {
        // Query products with reviews - requires cross-subgraph lookup
        // 1. Get products from products subgraph
        // 2. For each product, lookup reviews from reviews subgraph via productById
        @SuppressWarnings("unchecked")
        Map<String, Object> response = executeGraphQL("""
            {
              products {
                id
                name
                reviews {
                  id
                  text
                  stars
                }
              }
            }
            """);

        assertNotNull(response);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertNotNull(data, "Response should have data: " + response);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> products = (List<Map<String, Object>>) data.get("products");
        assertNotNull(products);
        assertFalse(products.isEmpty());

        // First product (Table) should have 2 reviews
        Map<String, Object> table = products.get(0);
        assertEquals("1", table.get("id"));
        assertEquals("Table", table.get("name"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tableReviews = (List<Map<String, Object>>) table.get("reviews");
        assertNotNull(tableReviews, "Product should have reviews");
        assertEquals(2, tableReviews.size(), "Table should have 2 reviews");

        // Verify review content
        Map<String, Object> review1 = tableReviews.get(0);
        assertEquals("101", review1.get("id"));
        assertEquals("Great table, very sturdy!", review1.get("text"));
        assertEquals(5, review1.get("stars"));
    }

    @Test
    @Order(5)
    void queryWithInlineFragment() {
        // Test inline fragment on same type
        @SuppressWarnings("unchecked")
        Map<String, Object> response = executeGraphQL("""
            {
              products {
                id
                ... on Product {
                  name
                  price
                }
              }
            }
            """);

        assertNotNull(response);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertNotNull(data, "Response should have data: " + response);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> products = (List<Map<String, Object>>) data.get("products");
        assertNotNull(products);
        assertFalse(products.isEmpty());

        Map<String, Object> firstProduct = products.get(0);
        assertEquals("1", firstProduct.get("id"));
        assertEquals("Table", firstProduct.get("name"));
        assertEquals(899, firstProduct.get("price"));
    }

    @Test
    @Order(6)
    void queryWithNamedFragment() {
        // Test named fragment
        @SuppressWarnings("unchecked")
        Map<String, Object> response = executeGraphQL("""
            {
              products {
                ...ProductFields
              }
            }

            fragment ProductFields on Product {
              id
              name
              price
            }
            """);

        assertNotNull(response);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertNotNull(data, "Response should have data: " + response);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> products = (List<Map<String, Object>>) data.get("products");
        assertNotNull(products);
        assertFalse(products.isEmpty());

        Map<String, Object> firstProduct = products.get(0);
        assertEquals("1", firstProduct.get("id"));
        assertEquals("Table", firstProduct.get("name"));
        assertEquals(899, firstProduct.get("price"));
    }

    @Test
    @Order(7)
    void queryWithNamedFragmentCrossSubgraph() {
        // Test named fragment with fields from multiple subgraphs
        @SuppressWarnings("unchecked")
        Map<String, Object> response = executeGraphQL("""
            {
              products {
                ...ProductWithReviews
              }
            }

            fragment ProductWithReviews on Product {
              id
              name
              reviews {
                ...ReviewFields
              }
            }

            fragment ReviewFields on Review {
              id
              text
              stars
            }
            """);

        assertNotNull(response);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertNotNull(data, "Response should have data: " + response);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> products = (List<Map<String, Object>>) data.get("products");
        assertNotNull(products);
        assertFalse(products.isEmpty());

        // First product (Table) should have reviews
        Map<String, Object> table = products.get(0);
        assertEquals("1", table.get("id"));
        assertEquals("Table", table.get("name"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tableReviews = (List<Map<String, Object>>) table.get("reviews");
        assertNotNull(tableReviews, "Product should have reviews");
        assertEquals(2, tableReviews.size(), "Table should have 2 reviews");

        Map<String, Object> review1 = tableReviews.get(0);
        assertEquals("101", review1.get("id"));
        assertEquals("Great table, very sturdy!", review1.get("text"));
        assertEquals(5, review1.get("stars"));
    }

    @Test
    @Order(8)
    void introspectionQueryReturnsCompleteSchema() {
        // Full introspection query with all options enabled
        String introspectionQuery = """
            query IntrospectionQuery {
              __schema {
                description
                queryType { name }
                mutationType { name }
                subscriptionType { name }
                types {
                  ...FullType
                }
                directives {
                  name
                  description
                  isRepeatable
                  locations
                  args(includeDeprecated: true) {
                    ...InputValue
                  }
                }
              }
            }

            fragment FullType on __Type {
              kind
              name
              description
              specifiedByURL
              isOneOf
              fields(includeDeprecated: true) {
                name
                description
                args(includeDeprecated: true) {
                  ...InputValue
                }
                type {
                  ...TypeRef
                }
                isDeprecated
                deprecationReason
              }
              inputFields(includeDeprecated: true) {
                ...InputValue
              }
              interfaces {
                ...TypeRef
              }
              enumValues(includeDeprecated: true) {
                name
                description
                isDeprecated
                deprecationReason
              }
              possibleTypes {
                ...TypeRef
              }
            }

            fragment InputValue on __InputValue {
              name
              description
              type {
                ...TypeRef
              }
              defaultValue
              isDeprecated
              deprecationReason
            }

            fragment TypeRef on __Type {
              kind
              name
              ofType {
                kind
                name
                ofType {
                  kind
                  name
                  ofType {
                    kind
                    name
                    ofType {
                      kind
                      name
                      ofType {
                        kind
                        name
                        ofType {
                          kind
                          name
                          ofType {
                            kind
                            name
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
            """;

        @SuppressWarnings("unchecked")
        Map<String, Object> response = executeGraphQL(introspectionQuery);

        assertNotNull(response);
        assertNotNull(response.get("data"), "Response should have data, got: " + response);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) data.get("__schema");
        assertNotNull(schema, "Should have __schema");

        // Verify query type
        @SuppressWarnings("unchecked")
        Map<String, Object> queryType = (Map<String, Object>) schema.get("queryType");
        assertNotNull(queryType);
        assertEquals("Query", queryType.get("name"));

        // No mutation or subscription in our test schema
        assertEquals(null, schema.get("mutationType"));
        assertEquals(null, schema.get("subscriptionType"));

        // Verify types exist
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> types = (List<Map<String, Object>>) schema.get("types");
        assertNotNull(types);
        assertFalse(types.isEmpty());

        // Find and verify the Query type
        Map<String, Object> queryTypeDef = findTypeByName(types, "Query");
        assertNotNull(queryTypeDef, "Should have Query type");
        assertEquals("OBJECT", queryTypeDef.get("kind"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> queryFields = (List<Map<String, Object>>) queryTypeDef.get("fields");
        assertNotNull(queryFields);

        // Query should have 'products' and 'reviews' fields (productById is internal lookup)
        Map<String, Object> productsField = findFieldByName(queryFields, "products");
        assertNotNull(productsField, "Query should have 'products' field");

        Map<String, Object> reviewsField = findFieldByName(queryFields, "reviews");
        assertNotNull(reviewsField, "Query should have 'reviews' field");

        // Find and verify the Product type
        Map<String, Object> productTypeDef = findTypeByName(types, "Product");
        assertNotNull(productTypeDef, "Should have Product type");
        assertEquals("OBJECT", productTypeDef.get("kind"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> productFields = (List<Map<String, Object>>) productTypeDef.get("fields");
        assertNotNull(productFields);

        // Product should have merged fields from both subgraphs: id, name, price, reviews
        assertNotNull(findFieldByName(productFields, "id"), "Product should have 'id' field");
        assertNotNull(findFieldByName(productFields, "name"), "Product should have 'name' field");
        assertNotNull(findFieldByName(productFields, "price"), "Product should have 'price' field");
        assertNotNull(findFieldByName(productFields, "reviews"), "Product should have 'reviews' field");

        // Find and verify the Review type
        Map<String, Object> reviewTypeDef = findTypeByName(types, "Review");
        assertNotNull(reviewTypeDef, "Should have Review type");
        assertEquals("OBJECT", reviewTypeDef.get("kind"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reviewFields = (List<Map<String, Object>>) reviewTypeDef.get("fields");
        assertNotNull(reviewFields);

        // Review should have: id, productId, text, stars
        assertNotNull(findFieldByName(reviewFields, "id"), "Review should have 'id' field");
        assertNotNull(findFieldByName(reviewFields, "productId"), "Review should have 'productId' field");
        assertNotNull(findFieldByName(reviewFields, "text"), "Review should have 'text' field");
        assertNotNull(findFieldByName(reviewFields, "stars"), "Review should have 'stars' field");

        // Verify directives exist
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> directives = (List<Map<String, Object>>) schema.get("directives");
        assertNotNull(directives);
        assertFalse(directives.isEmpty());

        // Should have standard directives like @skip, @include, @deprecated
        assertTrue(directives.stream().anyMatch(d -> "skip".equals(d.get("name"))),
            "Should have @skip directive");
        assertTrue(directives.stream().anyMatch(d -> "include".equals(d.get("name"))),
            "Should have @include directive");
        assertTrue(directives.stream().anyMatch(d -> "deprecated".equals(d.get("name"))),
            "Should have @deprecated directive");

        // Federation directives (@key, @lookup, @is) should NOT be exposed in introspection
        assertFalse(directives.stream().anyMatch(d -> "key".equals(d.get("name"))),
            "Federation directive @key should not be exposed");
        assertFalse(directives.stream().anyMatch(d -> "lookup".equals(d.get("name"))),
            "Federation directive @lookup should not be exposed");
        assertFalse(directives.stream().anyMatch(d -> "is".equals(d.get("name"))),
            "Federation directive @is should not be exposed");
    }

    @Test
    @Order(9)
    void typeIntrospectionQuery() {
        // Query a specific type using __type
        @SuppressWarnings("unchecked")
        Map<String, Object> response = executeGraphQL("""
            {
              __type(name: "Product") {
                kind
                name
                fields {
                  name
                  type {
                    kind
                    name
                    ofType {
                      kind
                      name
                    }
                  }
                }
              }
            }
            """);

        assertNotNull(response);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertNotNull(data, "Response should have data: " + response);

        @SuppressWarnings("unchecked")
        Map<String, Object> type = (Map<String, Object>) data.get("__type");
        assertNotNull(type, "Should have __type result");

        assertEquals("OBJECT", type.get("kind"));
        assertEquals("Product", type.get("name"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) type.get("fields");
        assertNotNull(fields);

        // Verify all Product fields are present
        List<String> fieldNames = fields.stream()
            .map(f -> (String) f.get("name"))
            .toList();

        assertTrue(fieldNames.contains("id"), "Should have id field");
        assertTrue(fieldNames.contains("name"), "Should have name field");
        assertTrue(fieldNames.contains("price"), "Should have price field");
        assertTrue(fieldNames.contains("reviews"), "Should have reviews field");
    }

    @Test
    @Order(10)
    void mixedIntrospectionAndDataQuery() {
        // Query that mixes introspection with actual data
        @SuppressWarnings("unchecked")
        Map<String, Object> response = executeGraphQL("""
            {
              __schema {
                queryType { name }
              }
              products {
                id
                name
              }
            }
            """);

        assertNotNull(response);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertNotNull(data, "Response should have data: " + response);

        // Verify introspection part
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) data.get("__schema");
        assertNotNull(schema, "Should have __schema");
        @SuppressWarnings("unchecked")
        Map<String, Object> queryType = (Map<String, Object>) schema.get("queryType");
        assertEquals("Query", queryType.get("name"));

        // Verify data part
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> products = (List<Map<String, Object>>) data.get("products");
        assertNotNull(products);
        assertFalse(products.isEmpty());

        Map<String, Object> firstProduct = products.get(0);
        assertEquals("1", firstProduct.get("id"));
        assertEquals("Table", firstProduct.get("name"));
    }

    private Map<String, Object> findTypeByName(List<Map<String, Object>> types, String name) {
        return types.stream()
            .filter(t -> name.equals(t.get("name")))
            .findFirst()
            .orElse(null);
    }

    private Map<String, Object> findFieldByName(List<Map<String, Object>> fields, String name) {
        return fields.stream()
            .filter(f -> name.equals(f.get("name")))
            .findFirst()
            .orElse(null);
    }

    @Test
    @Order(11)
    void queryWithInlineFragmentCrossSubgraph() {
        // Test inline fragment with cross-subgraph fields
        @SuppressWarnings("unchecked")
        Map<String, Object> response = executeGraphQL("""
            {
              products {
                id
                ... on Product {
                  name
                  reviews {
                    ... on Review {
                      id
                      text
                    }
                  }
                }
              }
            }
            """);

        assertNotNull(response);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertNotNull(data, "Response should have data: " + response);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> products = (List<Map<String, Object>>) data.get("products");
        assertNotNull(products);
        assertFalse(products.isEmpty());

        Map<String, Object> table = products.get(0);
        assertEquals("1", table.get("id"));
        assertEquals("Table", table.get("name"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reviews = (List<Map<String, Object>>) table.get("reviews");
        assertNotNull(reviews);
        assertFalse(reviews.isEmpty());

        Map<String, Object> review1 = reviews.get(0);
        assertEquals("101", review1.get("id"));
        assertEquals("Great table, very sturdy!", review1.get("text"));
    }

    @Test
    @Order(12)
    void skipDirectiveWithLiteralTrue() {
        // @skip(if: true) should exclude the field
        @SuppressWarnings("unchecked")
        Map<String, Object> response = executeGraphQL("""
            {
              products {
                id
                name @skip(if: true)
              }
            }
            """);

        assertNotNull(response);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertNotNull(data, "Response should have data: " + response);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> products = (List<Map<String, Object>>) data.get("products");
        assertNotNull(products);
        assertFalse(products.isEmpty());

        Map<String, Object> firstProduct = products.get(0);
        assertEquals("1", firstProduct.get("id"));
        assertFalse(firstProduct.containsKey("name"), "name field should be skipped");
    }

    @Test
    @Order(13)
    void includeDirectiveWithLiteralFalse() {
        // @include(if: false) should exclude the field
        @SuppressWarnings("unchecked")
        Map<String, Object> response = executeGraphQL("""
            {
              products {
                id
                price @include(if: false)
              }
            }
            """);

        assertNotNull(response);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertNotNull(data, "Response should have data: " + response);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> products = (List<Map<String, Object>>) data.get("products");
        assertNotNull(products);
        assertFalse(products.isEmpty());

        Map<String, Object> firstProduct = products.get(0);
        assertEquals("1", firstProduct.get("id"));
        assertFalse(firstProduct.containsKey("price"), "price field should not be included");
    }

    @Test
    @Order(14)
    void skipDirectiveWithVariableTrue() {
        // @skip(if: $skip) with $skip=true should exclude the field
        @SuppressWarnings("unchecked")
        Map<String, Object> response = executeGraphQL("""
            query GetProducts($skip: Boolean!) {
              products {
                id
                name @skip(if: $skip)
              }
            }
            """, Map.of("skip", true));

        assertNotNull(response);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertNotNull(data, "Response should have data: " + response);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> products = (List<Map<String, Object>>) data.get("products");
        assertNotNull(products);
        assertFalse(products.isEmpty());

        Map<String, Object> firstProduct = products.get(0);
        assertEquals("1", firstProduct.get("id"));
        assertFalse(firstProduct.containsKey("name"), "name field should be skipped when $skip=true");
    }

    @Test
    @Order(15)
    void skipDirectiveWithVariableFalse() {
        // @skip(if: $skip) with $skip=false should include the field
        @SuppressWarnings("unchecked")
        Map<String, Object> response = executeGraphQL("""
            query GetProducts($skip: Boolean!) {
              products {
                id
                name @skip(if: $skip)
              }
            }
            """, Map.of("skip", false));

        assertNotNull(response);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertNotNull(data, "Response should have data: " + response);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> products = (List<Map<String, Object>>) data.get("products");
        assertNotNull(products);
        assertFalse(products.isEmpty());

        Map<String, Object> firstProduct = products.get(0);
        assertEquals("1", firstProduct.get("id"));
        assertEquals("Table", firstProduct.get("name"), "name field should be present when $skip=false");
    }

    @Test
    @Order(16)
    void includeDirectiveWithVariableTrue() {
        // @include(if: $include) with $include=true should include the field
        @SuppressWarnings("unchecked")
        Map<String, Object> response = executeGraphQL("""
            query GetProducts($include: Boolean!) {
              products {
                id
                price @include(if: $include)
              }
            }
            """, Map.of("include", true));

        assertNotNull(response);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertNotNull(data, "Response should have data: " + response);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> products = (List<Map<String, Object>>) data.get("products");
        assertNotNull(products);
        assertFalse(products.isEmpty());

        Map<String, Object> firstProduct = products.get(0);
        assertEquals("1", firstProduct.get("id"));
        assertEquals(899, firstProduct.get("price"), "price field should be present when $include=true");
    }

    @Test
    @Order(17)
    void includeDirectiveWithVariableFalse() {
        // @include(if: $include) with $include=false should exclude the field
        @SuppressWarnings("unchecked")
        Map<String, Object> response = executeGraphQL("""
            query GetProducts($include: Boolean!) {
              products {
                id
                price @include(if: $include)
              }
            }
            """, Map.of("include", false));

        assertNotNull(response);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertNotNull(data, "Response should have data: " + response);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> products = (List<Map<String, Object>>) data.get("products");
        assertNotNull(products);
        assertFalse(products.isEmpty());

        Map<String, Object> firstProduct = products.get(0);
        assertEquals("1", firstProduct.get("id"));
        assertFalse(firstProduct.containsKey("price"), "price field should not be present when $include=false");
    }

    @Test
    @Order(18)
    void skipAndIncludeOnCrossSubgraphFields() {
        // Test @skip and @include on fields that require cross-subgraph lookup
        @SuppressWarnings("unchecked")
        Map<String, Object> response = executeGraphQL("""
            query GetProductsWithReviews($skipReviews: Boolean!, $includePrice: Boolean!) {
              products {
                id
                name
                price @include(if: $includePrice)
                reviews @skip(if: $skipReviews) {
                  id
                  text
                }
              }
            }
            """, Map.of("skipReviews", true, "includePrice", false));

        assertNotNull(response);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertNotNull(data, "Response should have data: " + response);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> products = (List<Map<String, Object>>) data.get("products");
        assertNotNull(products);
        assertFalse(products.isEmpty());

        Map<String, Object> firstProduct = products.get(0);
        assertEquals("1", firstProduct.get("id"));
        assertEquals("Table", firstProduct.get("name"));
        assertFalse(firstProduct.containsKey("price"), "price should not be included");
        assertFalse(firstProduct.containsKey("reviews"), "reviews should be skipped");
    }

    // ==================== Error Handling Tests ====================

    @Test
    @Order(19)
    void graphqlErrorFromSubgraphPropagates() {
        // Query that always fails with a GraphQL error
        @SuppressWarnings("unchecked")
        Map<String, Object> response = executeGraphQL("{ failingQuery }");

        assertNotNull(response);

        // Should have errors
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) response.get("errors");
        assertNotNull(errors, "Response should have errors: " + response);
        assertFalse(errors.isEmpty(), "Should have at least one error");

        // Verify error content
        Map<String, Object> error = errors.get(0);
        String message = (String) error.get("message");
        assertNotNull(message, "Error should have a message");
        assertTrue(message.contains("always fails"), "Error message should contain 'always fails': " + message);

        // Verify extensions are propagated
        @SuppressWarnings("unchecked")
        Map<String, Object> extensions = (Map<String, Object>) error.get("extensions");
        assertNotNull(extensions, "Error should have extensions: " + error);
        assertEquals("ALWAYS_FAILS", extensions.get("code"), "Error code should be propagated");
    }

    @Test
    @Order(20)
    void partialDataWithErrors() {
        // Query that returns partial data along with an error
        // Products from products subgraph should succeed, but failingQuery should fail
        @SuppressWarnings("unchecked")
        Map<String, Object> response = executeGraphQL("""
            {
              products {
                id
                name
              }
              failingQuery
            }
            """);

        assertNotNull(response);

        // Should have partial data
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertNotNull(data, "Response should have data: " + response);

        // Products should be present and valid
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> products = (List<Map<String, Object>>) data.get("products");
        assertNotNull(products, "Should have products data");
        assertFalse(products.isEmpty(), "Products should not be empty");
        assertEquals("1", products.get(0).get("id"));

        // failingQuery should be null due to error
        assertEquals(null, data.get("failingQuery"), "failingQuery should be null due to error");

        // Should have errors
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) response.get("errors");
        assertNotNull(errors, "Response should have errors");
        assertFalse(errors.isEmpty(), "Should have at least one error");
    }

    @Test
    @Order(21)
    void errorWithSpecificProductId() {
        // Query reviews for a product ID that causes an error
        @SuppressWarnings("unchecked")
        Map<String, Object> response = executeGraphQL("""
            {
              reviewsByProductId(productId: "error") {
                id
                text
              }
            }
            """);

        assertNotNull(response);

        // Should have errors
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) response.get("errors");
        assertNotNull(errors, "Response should have errors: " + response);
        assertFalse(errors.isEmpty(), "Should have at least one error");

        // Verify error content
        Map<String, Object> error = errors.get(0);
        String message = (String) error.get("message");
        assertTrue(message.contains("product not found"), "Error should mention product not found: " + message);

        // Verify extensions
        @SuppressWarnings("unchecked")
        Map<String, Object> extensions = (Map<String, Object>) error.get("extensions");
        assertNotNull(extensions, "Error should have extensions");
        assertEquals("PRODUCT_NOT_FOUND", extensions.get("code"));
    }

    @Test
    @Order(22)
    void successfulQueryAlongsideFailingQuery() {
        // Query that has both successful and failing parts across different subgraphs
        // Products query should succeed, failingQuery should fail
        @SuppressWarnings("unchecked")
        Map<String, Object> response = executeGraphQL("""
            {
              reviews {
                id
                text
              }
              failingQuery
            }
            """);

        assertNotNull(response);

        // Should have partial data
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertNotNull(data, "Response should have data: " + response);

        // Reviews should be present and valid
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reviews = (List<Map<String, Object>>) data.get("reviews");
        assertNotNull(reviews, "Should have reviews");
        assertFalse(reviews.isEmpty(), "reviews should not be empty");

        // failingQuery should be null due to error
        assertEquals(null, data.get("failingQuery"), "failingQuery should be null due to error");

        // Should have errors for the failing query
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) response.get("errors");
        assertNotNull(errors, "Response should have errors");
        assertFalse(errors.isEmpty(), "Should have at least one error");
    }

    @Test
    @Order(23)
    void reviewsQuerySuccessful() {
        // Verify the reviews query still works (sanity check before cross-subgraph error test)
        @SuppressWarnings("unchecked")
        Map<String, Object> response = executeGraphQL("""
            {
              reviewsByProductId(productId: "1") {
                id
                text
                stars
              }
            }
            """);

        assertNotNull(response);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertNotNull(data, "Response should have data: " + response);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reviews = (List<Map<String, Object>>) data.get("reviewsByProductId");
        assertNotNull(reviews, "Should have reviews");
        assertEquals(2, reviews.size(), "Product 1 should have 2 reviews");
    }

    // ==================== Custom Scalar Tests ====================

    @Test
    @Order(24)
    void queryProductsWithCustomScalarFields() {
        // Test custom scalar fields (DateTime, URL) from a single subgraph
        @SuppressWarnings("unchecked")
        Map<String, Object> response = executeGraphQL("""
            {
              products {
                id
                name
                createdAt
                imageUrl
              }
            }
            """);

        assertNotNull(response);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertNotNull(data, "Response should have data: " + response);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> products = (List<Map<String, Object>>) data.get("products");
        assertNotNull(products);
        assertEquals(3, products.size());

        Map<String, Object> table = products.get(0);
        assertEquals("1", table.get("id"));
        assertEquals("Table", table.get("name"));
        assertEquals("2025-01-15T10:30:00Z", table.get("createdAt"));
        assertEquals("https://example.com/images/table.jpg", table.get("imageUrl"));

        Map<String, Object> chair = products.get(1);
        assertEquals("2025-03-20T14:00:00Z", chair.get("createdAt"));
        assertEquals("https://example.com/images/chair.jpg", chair.get("imageUrl"));
    }

    @Test
    @Order(25)
    void queryReviewsWithCustomScalarField() {
        // Test custom scalar field (DateTime) from reviews subgraph
        @SuppressWarnings("unchecked")
        Map<String, Object> response = executeGraphQL("""
            {
              reviews {
                id
                text
                writtenAt
              }
            }
            """);

        assertNotNull(response);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertNotNull(data, "Response should have data: " + response);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reviews = (List<Map<String, Object>>) data.get("reviews");
        assertNotNull(reviews);
        assertFalse(reviews.isEmpty());

        Map<String, Object> firstReview = reviews.get(0);
        assertEquals("101", firstReview.get("id"));
        assertEquals("2025-02-10T08:00:00Z", firstReview.get("writtenAt"));
    }

    @Test
    @Order(26)
    void queryCustomScalarsCrossSubgraph() {
        // Test custom scalars across subgraph boundaries:
        // createdAt/imageUrl from products subgraph, writtenAt from reviews subgraph
        @SuppressWarnings("unchecked")
        Map<String, Object> response = executeGraphQL("""
            {
              products {
                id
                name
                createdAt
                imageUrl
                reviews {
                  id
                  text
                  writtenAt
                }
              }
            }
            """);

        assertNotNull(response);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertNotNull(data, "Response should have data: " + response);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> products = (List<Map<String, Object>>) data.get("products");
        assertNotNull(products);
        assertFalse(products.isEmpty());

        // Table: createdAt from products, reviews.writtenAt from reviews
        Map<String, Object> table = products.get(0);
        assertEquals("2025-01-15T10:30:00Z", table.get("createdAt"));
        assertEquals("https://example.com/images/table.jpg", table.get("imageUrl"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tableReviews = (List<Map<String, Object>>) table.get("reviews");
        assertNotNull(tableReviews);
        assertEquals(2, tableReviews.size());
        assertEquals("2025-02-10T08:00:00Z", tableReviews.get(0).get("writtenAt"));
        assertEquals("2025-02-15T12:30:00Z", tableReviews.get(1).get("writtenAt"));
    }

    @Test
    @Order(27)
    void queryWithCustomScalarArgument() {
        // Test custom scalar as query argument input
        @SuppressWarnings("unchecked")
        Map<String, Object> response = executeGraphQL("""
            query ProductsSince($since: DateTime!) {
              productsSince(since: $since) {
                id
                name
                createdAt
              }
            }
            """, Map.of("since", "2025-03-01T00:00:00Z"));

        assertNotNull(response);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertNotNull(data, "Response should have data: " + response);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> products = (List<Map<String, Object>>) data.get("productsSince");
        assertNotNull(products);
        // Only Chair (2025-03-20) and Couch (2025-06-01) are after 2025-03-01
        assertEquals(2, products.size(), "Should return products created after 2025-03-01: " + products);
        assertEquals("Chair", products.get(0).get("name"));
        assertEquals("Couch", products.get(1).get("name"));
    }

    @Test
    @Order(28)
    void introspectionIncludesCustomScalarTypes() {
        // Verify custom scalars appear in introspection
        @SuppressWarnings("unchecked")
        Map<String, Object> response = executeGraphQL("""
            {
              __schema {
                types {
                  name
                  kind
                }
              }
            }
            """);

        assertNotNull(response);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertNotNull(data, "Response should have data: " + response);

        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) data.get("__schema");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> types = (List<Map<String, Object>>) schema.get("types");

        // Verify DateTime scalar is present
        boolean hasDateTime = types.stream()
            .anyMatch(t -> "DateTime".equals(t.get("name")) && "SCALAR".equals(t.get("kind")));
        assertTrue(hasDateTime, "Schema should contain DateTime scalar type");

        // Verify URL scalar is present
        boolean hasUrl = types.stream()
            .anyMatch(t -> "URL".equals(t.get("name")) && "SCALAR".equals(t.get("kind")));
        assertTrue(hasUrl, "Schema should contain URL scalar type");
    }

    // ==================== DocumentProvider / Persisted Query Tests ====================

    @Test
    @Order(29)
    void persistedQueryWithKnownHash() {
        // Compute SHA-256 of "{ products { id } }" — same as TestDocumentProvider does
        String knownHash = sha256Hex("{ products { id } }");

        // Send request with extensions.persistedQuery.sha256Hash, no query field
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("extensions", Map.of(
            "persistedQuery", Map.of(
                "version", 1,
                "sha256Hash", knownHash
            )
        ));

        @SuppressWarnings("unchecked")
        Map<String, Object> response = gatewayClient.post()
            .uri("/graphql")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchangeToMono(r -> {
                if (r.statusCode().isError()) {
                    return r.bodyToMono(String.class)
                        .map(b -> {
                            throw new RuntimeException(
                                "GraphQL request failed with status " + r.statusCode() + ": " + b);
                        });
                }
                return r.bodyToMono(Map.class);
            })
            .block();

        assertNotNull(response);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertNotNull(data, "Response should have data: " + response);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> products = (List<Map<String, Object>>) data.get("products");
        assertNotNull(products, "Should have products");
        assertFalse(products.isEmpty(), "Products should not be empty");
        assertEquals("1", products.get(0).get("id"));
    }

    @Test
    @Order(30)
    void persistedQueryWithUnknownHash() {
        // Send request with an unknown hash
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("extensions", Map.of(
            "persistedQuery", Map.of(
                "version", 1,
                "sha256Hash", "unknown"
            )
        ));

        @SuppressWarnings("unchecked")
        Map<String, Object> response = gatewayClient.post()
            .uri("/graphql")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchangeToMono(r -> {
                if (r.statusCode().isError()) {
                    return r.bodyToMono(String.class)
                        .map(b -> {
                            throw new RuntimeException(
                                "GraphQL request failed with status " + r.statusCode() + ": " + b);
                        });
                }
                return r.bodyToMono(Map.class);
            })
            .block();

        assertNotNull(response);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) response.get("errors");
        assertNotNull(errors, "Response should have errors: " + response);
        assertFalse(errors.isEmpty(), "Should have at least one error");

        String message = (String) errors.get(0).get("message");
        assertTrue(message.contains("PersistedQueryNotFound"),
            "Error should contain PersistedQueryNotFound: " + message);
    }

    @Test
    @Order(31)
    void normalQueryFallsThroughWhenDocumentProviderRegistered() {
        // When no persisted query extension is present, DocumentProvider returns Mono.empty()
        // and the gateway falls through to normal ParseAndValidate
        Map<String, Object> response = executeGraphQL("{ products { id } }");

        assertNotNull(response, "Response should not be null");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertNotNull(data, "Response should have data even with DocumentProvider registered: " + response);
        assertNotNull(data.get("products"), "Should have products field");
    }

    private static String sha256Hex(String input) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private Map<String, Object> executeGraphQL(String query) {
        return executeGraphQL(query, null);
    }

    private Map<String, Object> executeGraphQL(String query, Map<String, Object> variables) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("query", query);
        if (variables != null) {
            body.put("variables", variables);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> response = gatewayClient.post()
            .uri("/graphql")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchangeToMono(r -> {
                if (r.statusCode().isError()) {
                    return r.bodyToMono(String.class)
                        .map(b -> {
                            throw new RuntimeException("GraphQL request failed with status " + r.statusCode() + ": " + b);
                        });
                }
                return r.bodyToMono(Map.class);
            })
            .block();
        return response;
    }

    private byte[] createSubgraphZip() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            // Products subgraph
            addResourceToZip(zos, "subgraphs/products/schema.graphqls");
            addResourceToZip(zos, "subgraphs/products/config.yaml");
            // Reviews subgraph
            addResourceToZip(zos, "subgraphs/reviews/schema.graphqls");
            addResourceToZip(zos, "subgraphs/reviews/config.yaml");
        }
        return baos.toByteArray();
    }

    private void addResourceToZip(ZipOutputStream zos, String resourcePath) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            zos.putNextEntry(new ZipEntry(resourcePath));
            is.transferTo(zos);
            zos.closeEntry();
        }
    }
}
