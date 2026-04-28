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
 * Tests for LookupDuplicateRule.
 *
 * The combination of return type + lookup arguments (as determined by @is) must be unique
 * per @lookup within a subgraph.
 */
class LookupDuplicateRuleTest {

    private static final String CODE = "LOOKUP_DUPLICATE";

    private SubgraphParser parser;
    private LookupDuplicateRule rule;

    @BeforeEach
    void setUp() {
        parser = new SubgraphParser();
        rule = new LookupDuplicateRule();
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

    // ========================================================================
    // Valid Cases
    // ========================================================================

    @Nested
    class ValidCases {

        @Test
        void singleLookup() {
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
        void multipleLookupsDifferentReturnTypes() {
            // Same lookup argument but different return types - valid
            Subgraph catalog = subgraph("catalog", """
                type Query {
                    productById(id: ID!): Product @lookup
                    categoryById(id: ID!): Category @lookup
                }
                type Product @key(fields: "id") {
                    id: ID!
                    name: String
                }
                type Category @key(fields: "id") {
                    id: ID!
                    name: String
                }
                """);

            assertValid(validate(catalog));
        }

        @Test
        void multipleLookupsDifferentLookupArgs() {
            // Same return type but different lookup arguments - valid
            Subgraph products = subgraph("products", """
                type Query {
                    productById(id: ID!): Product @lookup
                    productBySku(sku: String!): Product @lookup
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
        void multipleLookupsDifferentLookupArgsWithExplicitIs() {
            // Same return type, different lookup arguments via @is - valid
            Subgraph products = subgraph("products", """
                type Query {
                    productById(productId: ID! @is(field: "id")): Product @lookup
                    productBySku(productSku: String! @is(field: "sku")): Product @lookup
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
        void multipleLookupsDifferentCompositeLookupArgs() {
            // Same return type, different composite lookup arguments - valid
            Subgraph products = subgraph("products", """
                type Query {
                    productByIdAndVendor(id: ID!, vendor: String!): Product @lookup
                    productBySkuAndRegion(sku: String!, region: String!): Product @lookup
                }
                type Product @key(fields: "id vendor") {
                    id: ID!
                    sku: String!
                    vendor: String!
                    region: String!
                }
                """);

            assertValid(validate(products));
        }

        @Test
        void lookupsWithDisjointArgs() {
            // Lookups with completely different arguments - valid
            Subgraph products = subgraph("products", """
                type Query {
                    productById(id: ID!): Product @lookup
                    productByVendor(vendor: String!): Product @lookup
                }
                type Product @key(fields: "id") {
                    id: ID!
                    vendor: String!
                }
                """);

            assertValid(validate(products));
        }

        @Test
        void lookupsWithPartiallyOverlappingArgs() {
            // Lookups with different argument sets that partially overlap - valid
            // {id, vendor} and {vendor, region} share "vendor" but neither is a subset of the other
            Subgraph products = subgraph("products", """
                type Query {
                    productByIdAndVendor(id: ID!, vendor: String!): Product @lookup
                    productByVendorAndRegion(vendor: String!, region: String!): Product @lookup
                }
                type Product @key(fields: "id") {
                    id: ID!
                    vendor: String!
                    region: String!
                }
                """);

            assertValid(validate(products));
        }

        @Test
        void sameLookupArgsDifferentSubgraphs() {
            // Same return type and lookup arguments but in different subgraphs - valid
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
                    productById(id: ID!): Product @lookup
                }
                type Product @key(fields: "id") {
                    id: ID!
                    stock: Int
                }
                """);

            assertValid(validate(products, inventory));
        }
    }

    // ========================================================================
    // Invalid Cases - Explicit @is showing same lookup argument
    // ========================================================================

    @Nested
    class InvalidExplicitIs {

        @Test
        void duplicateLookupWithExplicitIs() {
            // Different argument names but same @is field mapping - invalid
            Subgraph products = subgraph("products", """
                type Query {
                    productById(id: ID!): Product @lookup
                    productByKey(key: ID! @is(field: "id")): Product @lookup
                }
                type Product @key(fields: "id") {
                    id: ID!
                    name: String
                }
                """);

            assertInvalid(validate(products));
        }

        @Test
        void duplicateLookupBothWithExplicitIs() {
            // Both have explicit @is mapping to same field - invalid
            Subgraph products = subgraph("products", """
                type Query {
                    productByProductId(productId: ID! @is(field: "id")): Product @lookup
                    productByKey(key: ID! @is(field: "id")): Product @lookup
                }
                type Product @key(fields: "id") {
                    id: ID!
                    name: String
                }
                """);

            assertInvalid(validate(products));
        }

        @Test
        void duplicateLookupCompositeLookupArgsWithExplicitIs() {
            // Composite lookup arguments, same fields via @is - invalid
            Subgraph products = subgraph("products", """
                type Query {
                    product(id: ID!, vendor: String!): Product @lookup
                    productLookup(
                        productId: ID! @is(field: "id")
                        vendorName: String! @is(field: "vendor")
                    ): Product @lookup
                }
                type Product @key(fields: "id vendor") {
                    id: ID!
                    vendor: String!
                }
                """);

            assertInvalid(validate(products));
        }
    }

    // ========================================================================
    // Invalid Cases - Implicit @is (argument name = field name)
    // ========================================================================

    @Nested
    class InvalidImplicitIs {

        @Test
        void duplicateLookupSameImplicitLookupArgs() {
            // Same argument names (implicit @is) - invalid
            Subgraph products = subgraph("products", """
                type Query {
                    product(id: ID!, vendor: String!): Product @lookup
                    productById(id: ID!, vendor: String!): Product @lookup
                }
                type Product @key(fields: "id vendor") {
                    id: ID!
                    vendor: String!
                }
                """);

            assertInvalid(validate(products));
        }

        @Test
        void duplicateLookupDifferentArgumentOrder() {
            // Same lookup arguments but different argument order - still invalid (order doesn't matter)
            Subgraph products = subgraph("products", """
                type Query {
                    productByIdAndVendor(id: ID!, vendor: String!): Product @lookup
                    productByVendorAndId(vendor: String!, id: ID!): Product @lookup
                }
                type Product @key(fields: "id vendor") {
                    id: ID!
                    vendor: String!
                }
                """);

            assertInvalid(validate(products));
        }
    }

    // ========================================================================
    // Invalid Cases - Mixed explicit and implicit @is
    // ========================================================================

    @Nested
    class InvalidMixedIs {

        @Test
        void duplicateLookupMixedExplicitImplicit() {
            // One implicit, one explicit - both map to same field - invalid
            Subgraph products = subgraph("products", """
                type Query {
                    productById(id: ID!): Product @lookup
                    productByProductId(productId: ID! @is(field: "id")): Product @lookup
                }
                type Product @key(fields: "id") {
                    id: ID!
                    name: String
                }
                """);

            assertInvalid(validate(products));
        }

        @Test
        void duplicateLookupMixedCompositeLookupArgs() {
            // Composite lookup arguments, mixed explicit/implicit - invalid
            Subgraph products = subgraph("products", """
                type Query {
                    product(id: ID!, vendor: String!): Product @lookup
                    productLookup(key: ID! @is(field: "id"), vendor: String!): Product @lookup
                }
                type Product @key(fields: "id vendor") {
                    id: ID!
                    vendor: String!
                }
                """);

            assertInvalid(validate(products));
        }
    }

    // ========================================================================
    // Invalid Cases - Subset/Superset (redundant lookups)
    // ========================================================================

    @Nested
    class InvalidSubsetSuperset {

        @Test
        void supersetLookupIsRedundant() {
            // If one lookup identifies the type with fewer arguments, adding more arguments is redundant
            Subgraph products = subgraph("products", """
                type Query {
                    productById(id: ID!): Product @lookup
                    productByIdAndVendor(id: ID!, vendor: String!): Product @lookup
                }
                type Product @key(fields: "id") {
                    id: ID!
                    vendor: String!
                }
                """);

            ValidationResult result = validate(products);
            assertInvalid(result);
            assertThat(result.errors().get(0).message())
                .contains("Redundant")
                .contains("productByIdAndVendor")
                .contains("productById")
                .contains("subset");
        }

        @Test
        void supersetLookupIsRedundantWithExplicitIs() {
            // Same test but with explicit @is mappings
            Subgraph products = subgraph("products", """
                type Query {
                    productByKey(key: ID! @is(field: "id")): Product @lookup
                    productByKeyAndCode(
                        key: ID! @is(field: "id")
                        code: String! @is(field: "vendor")
                    ): Product @lookup
                }
                type Product @key(fields: "id") {
                    id: ID!
                    vendor: String!
                }
                """);

            ValidationResult result = validate(products);
            assertInvalid(result);
            assertThat(result.errors().get(0).message())
                .contains("Redundant")
                .contains("subset");
        }

        @Test
        void supersetLookupWithThreeArguments() {
            // Multiple levels of redundancy
            Subgraph products = subgraph("products", """
                type Query {
                    productById(id: ID!): Product @lookup
                    productByIdAndVendor(id: ID!, vendor: String!): Product @lookup
                    productByIdVendorAndRegion(id: ID!, vendor: String!, region: String!): Product @lookup
                }
                type Product @key(fields: "id") {
                    id: ID!
                    vendor: String!
                    region: String!
                }
                """);

            ValidationResult result = validate(products);
            assertInvalid(result);
            // Should report multiple redundant lookups (both supersets of productById)
            assertThat(result.errors()).hasSizeGreaterThanOrEqualTo(2);
        }

        @Test
        void supersetLookupOnDifferentParentTypes() {
            // Subset/superset check should be per parent type
            // These are on different parent types, so the subset relationship doesn't apply
            Subgraph catalog = subgraph("catalog", """
                type Query {
                    productById(id: ID!): Product @lookup
                }
                type Admin {
                    productByIdAndVendor(id: ID!, vendor: String!): Product @lookup
                }
                type Product @key(fields: "id") {
                    id: ID!
                    vendor: String!
                }
                """);

            assertValid(validate(catalog));
        }

        @Test
        void supersetLookupOnDifferentReturnTypes() {
            // Subset relationship only matters for same return type
            Subgraph catalog = subgraph("catalog", """
                type Query {
                    productById(id: ID!): Product @lookup
                    categoryByIdAndName(id: ID!, name: String!): Category @lookup
                }
                type Product @key(fields: "id") {
                    id: ID!
                }
                type Category @key(fields: "id") {
                    id: ID!
                    name: String!
                }
                """);

            assertValid(validate(catalog));
        }

        @Test
        void supersetLookupWithNestedFieldPaths() {
            // Superset with nested field paths
            Subgraph products = subgraph("products", """
                type Query {
                    productByOwnerId(ownerId: ID! @is(field: "owner.id")): Product @lookup
                    productByOwnerIdAndName(
                        ownerId: ID! @is(field: "owner.id")
                        ownerName: String! @is(field: "owner.name")
                    ): Product @lookup
                }
                type Product @key(fields: "id") {
                    id: ID!
                    owner: User
                }
                type User {
                    id: ID!
                    name: String!
                }
                """);

            ValidationResult result = validate(products);
            assertInvalid(result);
            assertThat(result.errors().get(0).message())
                .contains("Redundant");
        }
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Nested
    class EdgeCases {

        @Test
        void moreThanTwoDuplicates() {
            // Three lookups with same signature - all should be reported
            Subgraph products = subgraph("products", """
                type Query {
                    productById(id: ID!): Product @lookup
                    productByKey(key: ID! @is(field: "id")): Product @lookup
                    productByProductId(productId: ID! @is(field: "id")): Product @lookup
                }
                type Product @key(fields: "id") {
                    id: ID!
                    name: String
                }
                """);

            ValidationResult result = validate(products);
            assertInvalid(result);
            // Should mention all three in the error message
            assertThat(result.errors().get(0).message())
                .contains("Query.productById")
                .contains("Query.productByKey")
                .contains("Query.productByProductId");
        }

        @Test
        void lookupWithRequireArgumentIgnored() {
            // @require arguments should be ignored for signature comparison
            Subgraph products = subgraph("products", """
                type Query {
                    productById(id: ID!): Product @lookup
                    productByIdWithExtra(id: ID!, extra: String @require(field: "name")): Product @lookup
                }
                type Product @key(fields: "id") {
                    id: ID!
                    name: String
                }
                """);

            // Both map to just "id" after ignoring @require, so they're duplicates
            assertInvalid(validate(products));
        }

        @Test
        void lookupOnDifferentParentTypes() {
            // Same lookup arguments but on different parent types - valid
            // (this is an unusual case but technically allowed)
            Subgraph catalog = subgraph("catalog", """
                type Query {
                    productById(id: ID!): Product @lookup
                }
                type Admin {
                    productById(id: ID!): Product @lookup
                }
                type Product @key(fields: "id") {
                    id: ID!
                }
                """);

            assertValid(validate(catalog));
        }

        @Test
        void nestedFieldPathsAreDifferent() {
            // Nested field paths should be compared - these are different
            Subgraph products = subgraph("products", """
                type Query {
                    productByOwnerId(ownerId: ID! @is(field: "owner.id")): Product @lookup
                    productByCreatorId(creatorId: ID! @is(field: "creator.id")): Product @lookup
                }
                type Product @key(fields: "id") {
                    id: ID!
                    owner: User
                    creator: User
                }
                type User {
                    id: ID!
                }
                """);

            assertValid(validate(products));
        }

        @Test
        void nestedFieldPathsDuplicate() {
            // Same nested field path - invalid
            Subgraph products = subgraph("products", """
                type Query {
                    productByOwnerId(ownerId: ID! @is(field: "owner.id")): Product @lookup
                    productByOwnerKey(ownerKey: ID! @is(field: "owner.id")): Product @lookup
                }
                type Product @key(fields: "id") {
                    id: ID!
                    owner: User
                }
                type User {
                    id: ID!
                }
                """);

            assertInvalid(validate(products));
        }

        @Test
        void internalLookupStillCountsAsDuplicate() {
            // An @internal @lookup with the same signature as a regular @lookup is still a duplicate
            Subgraph products = subgraph("products", """
                type Query {
                    productById(id: ID!): Product @lookup
                    internalLookup(code: ID! @is(field: "id")): Product @internal @lookup
                }
                type Product @key(fields: "id") {
                    id: ID!
                    name: String
                }
                """);

            assertInvalid(validate(products));
        }
    }
}
