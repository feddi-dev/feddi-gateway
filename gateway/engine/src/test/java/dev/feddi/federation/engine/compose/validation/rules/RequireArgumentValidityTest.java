package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Composer;
import dev.feddi.federation.engine.compose.CompositionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Path Field Argument Validity (Appendix A) for @require: the spec's examples, with width(unit: Unit!) in another
 * schema than the requiring one.
 */
class RequireArgumentValidityTest {

    private static List<String> requireErrors(String requirement) {
        CompositionResult result = new Composer().compose(List.of(
            Composer.SubgraphInput.of("a", """
                type Query {
                  products: [Product]
                  productById(id: ID!): Product @lookup @internal
                }
                enum Unit { IMPERIAL METRIC }
                type Product @key(fields: "id") {
                  id: ID!
                  width(unit: Unit!): Float!
                  height(unit: Unit = METRIC): Float!
                }
                """),
            Composer.SubgraphInput.of("b", """
                type Query { productById(id: ID!): Product @lookup @internal }
                type Product @key(fields: "id") {
                  id: ID!
                  shippingCost(width: Float @require(field: "%s")): Float
                }
                """.formatted(requirement))));
        return result.validationResult().errors().stream()
            .filter(d -> d.code().equals("REQUIRE_INVALID_FIELDS"))
            .map(d -> d.message())
            .toList();
    }

    @Test
    void definedArgumentWithValidValue() {
        assertThat(requireErrors("width(unit: IMPERIAL)")).isEmpty();
    }

    @Test
    void optionalArgumentWithDefaultMayBeOmitted() {
        assertThat(requireErrors("height")).isEmpty();
    }

    @Test
    void unknownArgument() {
        assertThat(requireErrors("width(scale: IMPERIAL)")).anyMatch(m -> m.contains("has no argument 'scale'"));
    }

    @Test
    void valueOfWrongType() {
        assertThat(requireErrors("width(unit: 3)")).anyMatch(m -> m.contains("invalid value 3"));
    }

    @Test
    void requiredArgumentMissing() {
        assertThat(requireErrors("width")).anyMatch(m -> m.contains("without its required argument 'unit'"));
    }
}
