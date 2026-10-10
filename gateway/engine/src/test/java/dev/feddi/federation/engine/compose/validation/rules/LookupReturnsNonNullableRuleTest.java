package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Composer;
import dev.feddi.federation.engine.compose.CompositionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for LookupReturnsNonNullableRule: the spec gives LOOKUP_RETURNS_NON_NULLABLE_TYPE the
 * severity WARNING, so a non-null lookup composes with a warning.
 */
class LookupReturnsNonNullableRuleTest {

    private static final String CODE = "LOOKUP_RETURNS_NON_NULLABLE_TYPE";

    private static CompositionResult compose(String productsSdl) {
        return new Composer().compose(List.of(
            Composer.SubgraphInput.of("products", productsSdl),
            Composer.SubgraphInput.of("reviews", """
                type Query {
                  reviews: [Review]
                }

                type Review {
                  id: ID!
                  product: Product
                }

                type Product @key(fields: "id") {
                  id: ID!
                }
                """)));
    }

    @Test
    void nonNullLookupComposesWithWarning() {
        CompositionResult result = compose("""
            type Query {
              productById(id: ID!): Product! @lookup
            }

            type Product @key(fields: "id") {
              id: ID!
              name: String
            }
            """);

        assertThat(result.isSuccess()).as("errors: %s", result.validationResult().errors()).isTrue();
        assertThat(result.validationResult().warnings())
            .anySatisfy(w -> {
                assertThat(w.code()).isEqualTo(CODE);
                assertThat(w.coordinate()).isEqualTo("Query.productById");
            });
    }

    @Test
    void nullableLookupHasNoWarning() {
        CompositionResult result = compose("""
            type Query {
              productById(id: ID!): Product @lookup
            }

            type Product @key(fields: "id") {
              id: ID!
              name: String
            }
            """);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.validationResult().warnings()).noneMatch(w -> w.code().equals(CODE));
    }
}
