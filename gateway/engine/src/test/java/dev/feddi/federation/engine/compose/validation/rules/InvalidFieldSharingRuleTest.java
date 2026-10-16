package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Composer;
import dev.feddi.federation.engine.compose.CompositionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for InvalidFieldSharingRule's exceptions: fields of a key (explicit, or inferred from a
 * lookup's arguments) and @internal types need no @shareable.
 */
class InvalidFieldSharingRuleTest {

    private static final String CODE = "INVALID_FIELD_SHARING";

    private static CompositionResult compose(String a, String b) {
        return new Composer().compose(List.of(Composer.SubgraphInput.of("a", a), Composer.SubgraphInput.of("b", b)));
    }

    private static List<String> sharingErrors(CompositionResult result) {
        return result.validationResult().errors().stream()
            .filter(d -> d.code().equals(CODE))
            .map(d -> d.coordinate())
            .distinct()
            .toList();
    }

    @Test
    void fieldMappedByLookupArgumentIsAKey() {
        CompositionResult result = compose("""
            type Query {
              topProduct: Product
              productById(id: ID!): Product @lookup @internal
            }

            type Product {
              id: ID!
              name: String!
            }
            """, """
            type Query {
              productByUpc(upc: ID!): Product @lookup
            }

            type Product {
              id: ID!
              upc: ID!
              price: Float!
            }
            """);

        assertThat(sharingErrors(result)).doesNotContain("Product.id");
    }

    @Test
    void lookupOnInterfaceMakesKeysOfItsImplementations() {
        CompositionResult result = compose("""
            type Query {
              topProduct: Product
              productById(id: ID!): Product @lookup @internal
            }

            type Product {
              id: ID!
              name: String!
            }
            """, """
            type Query {
              node(id: ID!): Node @lookup
            }

            interface Node {
              id: ID!
            }

            type Product implements Node {
              id: ID!
              price: Float!
            }
            """);

        assertThat(sharingErrors(result)).isEmpty();
    }

    @Test
    void lookupArgumentWithIsMapsToTheSelectedField() {
        CompositionResult result = compose("""
            type Query {
              products: [Product]
            }

            type Product {
              sku: ID!
              name: String!
            }
            """, """
            type Query {
              productBySku(code: ID! @is(field: "sku")): Product @lookup
            }

            type Product {
              sku: ID!
              price: Float!
            }
            """);

        assertThat(sharingErrors(result)).doesNotContain("Product.sku");
    }

    @Test
    void internalTypeIsExcluded() {
        CompositionResult result = compose("""
            type Query {
              lookups: Lookups @internal
              products: [Product]
            }

            type Lookups @internal {
              size: Int
            }

            type Product @key(fields: "id") {
              id: ID!
            }
            """, """
            type Query {
              lookups: Lookups @internal
              productById(id: ID!): Product @lookup
            }

            type Lookups @internal {
              size: Int
            }

            type Product @key(fields: "id") {
              id: ID!
              name: String
            }
            """);

        assertThat(sharingErrors(result)).doesNotContain("Lookups.size");
    }

    @Test
    void lookupInSeveralSchemasNeedsShareable() {
        CompositionResult result = compose("""
            type Query {
              productById(id: ID!): Product @lookup
            }

            type Product @key(fields: "id") {
              id: ID!
              name: String
            }
            """, """
            type Query {
              productById(id: ID!): Product @lookup
            }

            type Product @key(fields: "id") {
              id: ID!
              price: Float
            }
            """);

        assertThat(sharingErrors(result)).containsExactly("Query.productById");
    }

    @Test
    void lookupInSeveralSchemasMayBeShareableOrInternal() {
        CompositionResult shareable = compose("""
            type Query {
              productById(id: ID!): Product @lookup @shareable
            }

            type Product @key(fields: "id") {
              id: ID!
              name: String
            }
            """, """
            type Query {
              productById(id: ID!): Product @lookup @shareable
            }

            type Product @key(fields: "id") {
              id: ID!
              price: Float
            }
            """);
        CompositionResult internal = compose("""
            type Query {
              productById(id: ID!): Product @lookup
            }

            type Product @key(fields: "id") {
              id: ID!
              name: String
            }
            """, """
            type Query {
              productById(id: ID!): Product @lookup @internal
            }

            type Product @key(fields: "id") {
              id: ID!
              price: Float
            }
            """);

        assertThat(sharingErrors(shareable)).isEmpty();
        assertThat(sharingErrors(internal)).isEmpty();
    }

    @Test
    void nonKeyFieldInSeveralSchemasNeedsShareable() {
        CompositionResult result = compose("""
            type Query {
              products: [Product]
            }

            type Product @key(fields: "id") {
              id: ID!
              name: String
            }
            """, """
            type Query {
              productById(id: ID!): Product @lookup
            }

            type Product @key(fields: "id") {
              id: ID!
              name: String
            }
            """);

        assertThat(sharingErrors(result)).containsExactly("Product.name");
    }

    @Test
    void overriddenFieldNeedsNoShareable() {
        CompositionResult result = compose("""
            type Query {
              posts: [TextPost]
              textPostById(id: ID!): TextPost @lookup @internal
            }

            type TextPost @key(fields: "id") {
              id: ID!
              createdAt: String!
            }
            """, """
            type Query {
              textPostById(id: ID!): TextPost @lookup @internal
            }

            type TextPost @key(fields: "id") {
              id: ID!
              createdAt: String! @override(from: "a")
            }
            """);

        assertThat(sharingErrors(result)).doesNotContain("TextPost.createdAt");
    }
}
