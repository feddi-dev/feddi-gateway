package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Composer;
import dev.feddi.federation.engine.compose.CompositionResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the spec's satisfiability algorithm (PlanOptions over query paths): what the per-node check of
 * feddi's earlier implementation got wrong, and the cases the spec rejects.
 */
class SatisfiabilityValidationRuleTest {

    private static final String CODE = "UNSATISFIABLE_QUERY_PATH";

    private static CompositionResult compose(String... namesAndSchemas) {
        List<Composer.SubgraphInput> inputs = new ArrayList<>();
        for (int i = 0; i < namesAndSchemas.length; i += 2) {
            inputs.add(Composer.SubgraphInput.of(namesAndSchemas[i], namesAndSchemas[i + 1]));
        }
        return new Composer().compose(inputs);
    }

    private static List<String> unsatisfiable(CompositionResult result) {
        return result.validationResult().errors().stream()
            .filter(d -> d.code().equals(CODE))
            .map(d -> d.coordinate())
            .distinct()
            .toList();
    }

    @Test
    void sharedRootFieldResolvesEachSchemasFieldsInThatSchema() {
        // No lookups: Product.name is only reachable from the "name" schema's Query.product, which is enough.
        CompositionResult result = compose("category", """
            type Query { product: Product! @shareable }
            type Product { id: ID! }
            """, "name", """
            type Query { product: Product! @shareable }
            type Product { name: String! }
            """);

        assertThat(unsatisfiable(result)).isEmpty();
    }

    @Test
    void unionMemberOnlyAnotherSchemaReturnsIsNoPathFromThisField() {
        // a's media never returns a Movie: Query.media.bTitle on Movie is no executable path.
        CompositionResult result = compose("a", """
            type Query { media: Media }
            union Media = Book
            type Book @key(fields: "id") { id: ID! title: String! @shareable }
            """, "b", """
            type Query {
              bookById(id: ID!): Book @lookup @internal
              movieById(id: ID!): Movie @lookup @internal
            }
            union Media = Book | Movie
            type Book @key(fields: "id") { id: ID! title: String! @shareable }
            type Movie @key(fields: "id") { id: ID! bTitle: String! }
            """);

        assertThat(unsatisfiable(result)).isEmpty();
    }

    @Test
    void providesIsIgnored() {
        // Category.id is external in a and only provided there: no lookup into b can get its key.
        CompositionResult result = compose("a", """
            type Query { products: [Product] }
            type Product @key(fields: "id") {
              id: ID!
              category: Category @provides(fields: "id")
            }
            type Category @key(fields: "id") { id: ID! @external }
            """, "b", """
            type Query { categoryById(id: ID!): Category @lookup @internal }
            type Category @key(fields: "id") { id: ID! name: String }
            """);

        assertThat(unsatisfiable(result)).contains("Category.name");
    }

    @Test
    void requirementMayNeedAnotherRequirement() {
        // byExpert (a) requires byNovice (b), which requires author.yearsOfExperience (a).
        CompositionResult result = compose("a", """
            type Query {
              feed: [Post]
              postById(id: ID!): Post @lookup @internal
            }
            type Post @key(fields: "id") {
              id: ID!
              author: Author!
              byExpert(byNovice: Boolean! @require(field: "byNovice")): Boolean!
            }
            type Author { yearsOfExperience: Int! }
            """, "b", """
            type Query { postById(id: ID!): Post @lookup @internal }
            type Post @key(fields: "id") {
              id: ID!
              byNovice(yearsOfExperience: Int! @require(field: "author.yearsOfExperience")): Boolean!
            }
            """);

        assertThat(unsatisfiable(result)).isEmpty();
    }

    @Test
    void requirementOnlyTheRequiringSchemaHasIsUnsatisfiable() {
        CompositionResult result = compose("a", """
            type Query {
              feed: [Post]
              postById(id: ID!): Post @lookup @internal
            }
            type Post @key(fields: "id") { id: ID! }
            """, "b", """
            type Query { postById(id: ID!): Post @lookup @internal }
            type Post @key(fields: "id") {
              id: ID!
              size: Int!
              pages(size: Int! @require(field: "size")): Int!
            }
            """);

        assertThat(result.isSuccess()).isFalse();
    }
}
