package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.SubgraphParser;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for RequireInvalidUsageRule.
 *
 * The @require directive cannot be used on @lookup field arguments because:
 * - @lookup fields are entry points for entity resolution
 * - @lookup fields use @is to map arguments to key fields
 * - @require is for entity fields that need pre-fetched data to resolve
 */
class RequireInvalidUsageRuleTest {

    private static final String CODE = "REQUIRE_INVALID_USAGE";

    private SubgraphParser parser;
    private RequireInvalidUsageRule rule;

    @BeforeEach
    void setUp() {
        parser = new SubgraphParser();
        rule = new RequireInvalidUsageRule();
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
    // Valid Cases - @require on non-@lookup fields
    // ========================================================================

    @Test
    void valid_requireOnEntityField() {
        // @require on entity field arguments is valid
        Subgraph products = subgraph("products", """
            type Query {
              products: [Product]
            }
            type Product @key(fields: "id") {
              id: ID!
              name: String
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
    void valid_lookupWithoutRequire() {
        // @lookup without @require is valid
        Subgraph products = subgraph("products", """
            type Query {
              productById(id: ID!): Product @lookup
            }
            type Product @key(fields: "id") {
              id: ID!
              name: String
            }
            """);

        assertValid(validate(products));
    }

    @Test
    void valid_lookupWithIs() {
        // @lookup with @is is valid
        Subgraph products = subgraph("products", """
            type Query {
              productBySku(sku: String! @is(field: "sku")): Product @lookup
            }
            type Product @key(fields: "id") {
              id: ID!
              sku: String!
              name: String
            }
            """);

        assertValid(validate(products));
    }

    @Test
    void valid_lookupWithMultipleIsArgs() {
        // @lookup with multiple @is arguments is valid
        Subgraph shipping = subgraph("shipping", """
            type Query {
              shipmentByTracking(
                tracking: String! @is(field: "trackingNumber")
                carrier: String! @is(field: "carrier")
              ): Shipment @lookup
            }
            type Shipment @key(fields: "trackingNumber carrier") {
              trackingNumber: String!
              carrier: String!
              status: String
            }
            """);

        assertValid(validate(shipping));
    }

    // ========================================================================
    // Invalid Cases - @require on @lookup fields
    // ========================================================================

    @Test
    void invalid_requireOnLookupField() {
        // @require on @lookup field argument is invalid
        Subgraph products = subgraph("products", """
            type Query {
              productById(id: ID!, extra: String @require(field: "name")): Product @lookup
            }
            type Product @key(fields: "id") {
              id: ID!
              name: String
            }
            """);

        assertInvalidWithCoordinate(validate(products), "Query.productById(extra:)");
    }

    @Test
    void invalid_requireOnLookupFieldWithIs() {
        // @require + @is on same @lookup field - @require is still invalid
        Subgraph products = subgraph("products", """
            type Query {
              productBySku(
                sku: String! @is(field: "sku")
                extra: String @require(field: "name")
              ): Product @lookup
            }
            type Product @key(fields: "id") {
              id: ID!
              sku: String!
              name: String
            }
            """);

        assertInvalidWithCoordinate(validate(products), "Query.productBySku(extra:)");
    }

    @Test
    void invalid_multipleRequireOnLookupField() {
        // Multiple @require arguments on @lookup field - all are invalid
        Subgraph products = subgraph("products", """
            type Query {
              productById(
                id: ID!
                extra1: String @require(field: "name")
                extra2: Int @require(field: "price")
              ): Product @lookup
            }
            type Product @key(fields: "id") {
              id: ID!
              name: String
              price: Int
            }
            """);

        ValidationResult result = validate(products);
        assertThat(result.errors())
            .filteredOn(d -> d.code().equals(CODE))
            .hasSize(2);
    }

    @Test
    void invalid_requireOnLookupFieldOnInterface() {
        // @require on @lookup field on interface is also invalid
        Subgraph catalog = subgraph("catalog", """
            interface Searchable @key(fields: "id") {
              id: ID!
            }
            type Query {
              searchById(id: ID!, filter: String @require(field: "name")): Searchable @lookup
            }
            type Product implements Searchable @key(fields: "id") {
              id: ID!
              name: String
            }
            """);

        assertInvalidWithCoordinate(validate(catalog), "Query.searchById(filter:)");
    }

    @Test
    void invalid_requireOnLookupFieldOnObjectType() {
        // @lookup on object type field (not just Query) with @require is invalid
        Subgraph catalog = subgraph("catalog", """
            type Query {
              categories: [Category]
            }
            type Category @key(fields: "id") {
              id: ID!
              productById(id: ID!, extra: String @require(field: "name")): Product @lookup
            }
            type Product @key(fields: "id") {
              id: ID!
              name: String
            }
            """);

        assertInvalidWithCoordinate(validate(catalog), "Category.productById(extra:)");
    }

    // ========================================================================
    // Mixed Cases - some valid, some invalid
    // ========================================================================

    @Test
    void mixed_requireOnLookupAndEntityField() {
        // @require on @lookup is invalid, but @require on entity field is valid
        Subgraph products = subgraph("products", """
            type Query {
              productById(id: ID!, extra: String @require(field: "name")): Product @lookup
            }
            type Product @key(fields: "id") {
              id: ID!
              name: String
              shippingCost(weight: Float @require(field: "weight")): Float
            }
            """);

        ValidationResult result = validate(products);
        // Should have exactly one error - for the @lookup field
        assertThat(result.errors())
            .filteredOn(d -> d.code().equals(CODE))
            .hasSize(1)
            .allMatch(d -> d.coordinate().equals("Query.productById(extra:)"));
    }

    @Test
    void valid_multipleSubgraphsNoRequireOnLookup() {
        // Multiple subgraphs, all valid
        Subgraph products = subgraph("products", """
            type Query {
              productById(id: ID!): Product @lookup
            }
            type Product @key(fields: "id") {
              id: ID!
              name: String
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
    void invalid_multipleSubgraphsOneWithRequireOnLookup() {
        // One subgraph has invalid @require on @lookup
        Subgraph products = subgraph("products", """
            type Query {
              productById(id: ID!): Product @lookup
            }
            type Product @key(fields: "id") {
              id: ID!
              name: String
            }
            """);

        Subgraph inventory = subgraph("inventory", """
            type Query {
              productByBarcode(code: String!, extra: Int @require(field: "stock")): Product @lookup
            }
            type Product @key(fields: "id") {
              id: ID!
              stock: Int
            }
            """);

        ValidationResult result = validate(products, inventory);
        assertInvalidWithCoordinate(result, "Query.productByBarcode(extra:)");
    }
}
