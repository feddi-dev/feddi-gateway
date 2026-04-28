package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.SubgraphParser;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for RequireOnNonEntityRule.
 *
 * The @require directive can only be used on fields of entity types.
 * Entity types are types that are returned by @lookup fields in any subgraph.
 */
class RequireOnNonEntityRuleTest {

    private static final String CODE = "REQUIRE_ON_NON_ENTITY";

    private SubgraphParser parser;
    private RequireOnNonEntityRule rule;

    @BeforeEach
    void setUp() {
        parser = new SubgraphParser();
        rule = new RequireOnNonEntityRule();
    }

    private Subgraph subgraph(String name, String sdl) {
        return parser.parse(name, "http://" + name, sdl);
    }

    private ValidationResult validate(Subgraph... subgraphs) {
        return rule.validate(List.of(subgraphs));
    }

    private void assertValid(ValidationResult result) {
        assertThat(result.isValid())
            .as("Expected validation to pass but got errors: %s", result.errors())
            .isTrue();
    }

    private void assertInvalid(ValidationResult result) {
        assertThat(result.hasErrors())
            .as("Expected validation to fail")
            .isTrue();
        assertThat(result.errors())
            .anyMatch(d -> d.code().equals(CODE));
    }

    private void assertInvalidWithCoordinate(ValidationResult result, String coordinate) {
        assertInvalid(result);
        assertThat(result.errors())
            .anyMatch(d -> d.code().equals(CODE) && d.coordinate().equals(coordinate));
    }

    // ========================================================================
    // Valid Cases - @require on entity type fields
    // ========================================================================

    @Nested
    class ValidCases {

        @Test
        void requireOnEntityField_sameSubgraph() {
            // Product is an entity because it has @lookup
            Subgraph products = subgraph("products", """
                type Query {
                  productById(id: ID!): Product @lookup
                }
                type Product @key(fields: "id") {
                  id: ID!
                  name: String
                  shippingCost(weight: Float @require(field: "weight")): Float
                }
                """);

            assertValid(validate(products));
        }

        @Test
        void requireOnEntityField_differentSubgraph() {
            // Product is an entity because products subgraph has @lookup
            Subgraph products = subgraph("products", """
                type Query {
                  productById(id: ID!): Product @lookup
                }
                type Product @key(fields: "id") {
                  id: ID!
                  name: String
                  weight: Float
                }
                """);

            Subgraph shipping = subgraph("shipping", """
                type Product @key(fields: "id") {
                  id: ID!
                  shippingCost(weight: Float @require(field: "weight")): Float
                }
                """);

            assertValid(validate(products, shipping));
        }

        @Test
        void requireOnEntityField_multipleLookups() {
            // Product is an entity through multiple @lookup fields
            Subgraph products = subgraph("products", """
                type Query {
                  productById(id: ID!): Product @lookup
                  productBySku(sku: String!): Product @lookup
                }
                type Product @key(fields: "id") {
                  id: ID!
                  sku: String!
                  name: String
                  price(currency: String @require(field: "baseCurrency")): Float
                }
                """);

            assertValid(validate(products));
        }

        @Test
        void requireOnInterfaceEntityField() {
            // Content is an entity via @lookup
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  contentById(id: ID!): Content @lookup
                }
                interface Content @key(fields: "id") {
                  id: ID!
                  title: String
                  related(category: String @require(field: "categoryId")): [Content]
                }
                type Article implements Content @key(fields: "id") {
                  id: ID!
                  title: String
                  categoryId: String
                  related(category: String @require(field: "categoryId")): [Content]
                }
                """);

            assertValid(validate(catalog));
        }

        @Test
        void multipleRequireOnSameEntityField() {
            Subgraph products = subgraph("products", """
                type Query {
                  productById(id: ID!): Product @lookup
                }
                type Product @key(fields: "id") {
                  id: ID!
                  name: String
                  fullPrice(
                    basePrice: Float @require(field: "price"),
                    taxRate: Float @require(field: "tax")
                  ): Float
                }
                """);

            assertValid(validate(products));
        }

        @Test
        void noRequireDirectives() {
            // No @require at all - always valid
            Subgraph products = subgraph("products", """
                type Query {
                  products: [Product]
                }
                type Product {
                  id: ID!
                  name: String
                }
                """);

            assertValid(validate(products));
        }
    }

    // ========================================================================
    // Invalid Cases - @require on non-entity type fields
    // ========================================================================

    @Nested
    class InvalidCases {

        @Test
        void requireOnNonEntityType_noLookup() {
            // Product has no @lookup, so it's not an entity
            Subgraph products = subgraph("products", """
                type Query {
                  products: [Product]
                }
                type Product @key(fields: "id") {
                  id: ID!
                  name: String
                  shippingCost(weight: Float @require(field: "weight")): Float
                }
                """);

            assertInvalidWithCoordinate(validate(products), "Product.shippingCost(weight:)");
        }

        @Test
        void requireOnQueryField() {
            // Query type is never an entity
            Subgraph api = subgraph("api", """
                type Query {
                  search(term: String!, filter: String @require(field: "category")): [Result]
                }
                type Result {
                  id: ID!
                  name: String
                }
                """);

            assertInvalidWithCoordinate(validate(api), "Query.search(filter:)");
        }

        @Test
        void requireOnMutationField() {
            // Mutation type is never an entity
            Subgraph api = subgraph("api", """
                type Query {
                  dummy: String
                }
                type Mutation {
                  createProduct(
                    name: String!,
                    defaultPrice: Float @require(field: "basePrice")
                  ): Product
                }
                type Product {
                  id: ID!
                  name: String
                }
                """);

            assertInvalidWithCoordinate(validate(api), "Mutation.createProduct(defaultPrice:)");
        }

        @Test
        void requireOnNestedNonEntityType() {
            // Product is an entity, but Address is not
            Subgraph products = subgraph("products", """
                type Query {
                  productById(id: ID!): Product @lookup
                }
                type Product @key(fields: "id") {
                  id: ID!
                  address: Address
                }
                type Address {
                  city: String
                  formatted(includeZip: Boolean @require(field: "zipCode")): String
                }
                """);

            assertInvalidWithCoordinate(validate(products), "Address.formatted(includeZip:)");
        }

        @Test
        void requireOnTypeWithKeyButNoLookup() {
            // Having @key doesn't make a type an entity - only @lookup does
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  categories: [Category]
                }
                type Category @key(fields: "id") {
                  id: ID!
                  name: String
                  products(filter: String @require(field: "filterDefault")): [Product]
                }
                type Product {
                  id: ID!
                }
                """);

            assertInvalidWithCoordinate(validate(catalog), "Category.products(filter:)");
        }

        @Test
        void multipleInvalidRequires() {
            Subgraph api = subgraph("api", """
                type Query {
                  search(term: String!, filter: String @require(field: "f")): [Result]
                }
                type Result {
                  id: ID!
                  computed(x: Int @require(field: "y")): Int
                }
                """);

            ValidationResult result = validate(api);
            assertThat(result.errors())
                .filteredOn(d -> d.code().equals(CODE))
                .hasSize(2);
        }
    }

    // ========================================================================
    // Cross-Subgraph Cases
    // ========================================================================

    @Nested
    class CrossSubgraphCases {

        @Test
        void entityDefinedInOtherSubgraph_valid() {
            // Product is an entity via @lookup in products subgraph
            // shipping subgraph can use @require on Product fields
            Subgraph products = subgraph("products", """
                type Query {
                  productById(id: ID!): Product @lookup
                }
                type Product @key(fields: "id") {
                  id: ID!
                  name: String
                  weight: Float
                }
                """);

            Subgraph shipping = subgraph("shipping", """
                type Product @key(fields: "id") {
                  id: ID!
                  shippingCost(weight: Float @require(field: "weight")): Float
                }
                """);

            assertValid(validate(products, shipping));
        }

        @Test
        void entityInOneSubgraph_nonEntityInAnother() {
            // Product is an entity, Order is not
            Subgraph products = subgraph("products", """
                type Query {
                  productById(id: ID!): Product @lookup
                }
                type Product @key(fields: "id") {
                  id: ID!
                  price(currency: String @require(field: "baseCurrency")): Float
                }
                """);

            Subgraph orders = subgraph("orders", """
                type Query {
                  orders: [Order]
                }
                type Order {
                  id: ID!
                  total(tax: Float @require(field: "taxRate")): Float
                }
                """);

            ValidationResult result = validate(products, orders);
            // Product.price is valid, Order.total is invalid
            assertThat(result.errors())
                .filteredOn(d -> d.code().equals(CODE))
                .hasSize(1)
                .allMatch(d -> d.coordinate().equals("Order.total(tax:)"));
        }

        @Test
        void lookupInOneSubgraph_requireInAnother_valid() {
            // @lookup in one subgraph makes type an entity globally
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  bookById(id: ID!): Book @lookup
                }
                type Book @key(fields: "id") {
                  id: ID!
                  title: String
                  authorId: ID
                }
                """);

            Subgraph reviews = subgraph("reviews", """
                type Book @key(fields: "id") {
                  id: ID!
                  averageRating(minReviews: Int @require(field: "reviewCount")): Float
                }
                """);

            Subgraph recommendations = subgraph("recommendations", """
                type Book @key(fields: "id") {
                  id: ID!
                  similar(genre: String @require(field: "primaryGenre")): [Book]
                }
                """);

            assertValid(validate(catalog, reviews, recommendations));
        }
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Nested
    class EdgeCases {

        @Test
        void unionEntityType() {
            // SearchResult is an entity via @lookup
            Subgraph search = subgraph("search", """
                type Query {
                  searchResultById(id: ID!): SearchResult @lookup
                }
                union SearchResult = Product | Article
                type Product @key(fields: "id") {
                  id: ID!
                  name: String
                  highlight(term: String @require(field: "searchTerm")): String
                }
                type Article @key(fields: "id") {
                  id: ID!
                  title: String
                }
                """);

            // Product is part of union entity, so @require is valid
            assertValid(validate(search));
        }

        @Test
        void interfaceImplementorIsEntity() {
            // Only Article has @lookup, so Article is entity but Content interface is too
            Subgraph content = subgraph("content", """
                type Query {
                  articleById(id: ID!): Article @lookup
                }
                interface Content @key(fields: "id") {
                  id: ID!
                  title: String
                }
                type Article implements Content @key(fields: "id") {
                  id: ID!
                  title: String
                  related(tag: String @require(field: "primaryTag")): [Content]
                }
                """);

            assertValid(validate(content));
        }

        @Test
        void emptySubgraphList() {
            ValidationResult result = rule.validate(List.of());
            assertValid(result);
        }
    }
}
