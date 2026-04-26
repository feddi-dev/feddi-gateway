package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.SchemaMerger;
import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.SubgraphParser;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import graphql.schema.GraphQLSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for InterfaceHasNoImplementationsRule.
 *
 * Validates that interface types used as return types have at least one implementing type.
 */
class InterfaceHasNoImplementationsRuleTest {

    private static final String CODE = "INTERFACE_HAS_NO_IMPLEMENTATIONS";

    private SubgraphParser parser;
    private SchemaMerger merger;
    private InterfaceHasNoImplementationsRule rule;

    @BeforeEach
    void setUp() {
        parser = new SubgraphParser();
        merger = new SchemaMerger();
        rule = new InterfaceHasNoImplementationsRule();
    }

    private Subgraph subgraph(String name, String sdl) {
        return parser.parse(name, "http://" + name, sdl);
    }

    private ValidationResult validate(Subgraph... subgraphs) {
        List<Subgraph> subgraphList = List.of(subgraphs);
        List<GraphQLSchema> schemas = subgraphList.stream()
            .map(Subgraph::schema)
            .toList();
        GraphQLSchema mergedSchema = merger.mergeAll(schemas);
        return rule.validate(mergedSchema, subgraphList);
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
    // Valid Cases - Interface has implementations
    // ========================================================================

    @Nested
    class ValidCases {

        @Test
        void interfaceWithImplementation() {
            Subgraph catalog = subgraph("catalog", """
                type Query {
                    media: Media
                }
                interface Media {
                    id: ID!
                }
                type Movie implements Media {
                    id: ID!
                    title: String
                }
                """);

            ValidationResult result = validate(catalog);
            assertValid(result);
        }

        @Test
        void interfaceWithMultipleImplementations() {
            Subgraph catalog = subgraph("catalog", """
                type Query {
                    media: [Media]
                }
                interface Media {
                    id: ID!
                }
                type Movie implements Media {
                    id: ID!
                }
                type TVShow implements Media {
                    id: ID!
                }
                """);

            ValidationResult result = validate(catalog);
            assertValid(result);
        }

        @Test
        void interfaceWithImplementationFromDifferentSubgraph() {
            Subgraph catalog = subgraph("catalog", """
                type Query {
                    media: Media
                }
                interface Media {
                    id: ID!
                }
                """);

            Subgraph movies = subgraph("movies", """
                interface Media {
                    id: ID!
                }
                type Movie implements Media {
                    id: ID!
                    title: String
                }
                """);

            ValidationResult result = validate(catalog, movies);
            assertValid(result);
        }

        @Test
        void unusedInterfaceWithNoImplementation() {
            // Interface is defined but not used as a return type - this is allowed
            Subgraph catalog = subgraph("catalog", """
                type Query {
                    movie: Movie
                }
                interface Media {
                    id: ID!
                }
                type Movie {
                    id: ID!
                }
                """);

            ValidationResult result = validate(catalog);
            assertValid(result);
        }

        @Test
        void inaccessibleInterfaceWithNoImplementation() {
            // @inaccessible interfaces are skipped
            // When the interface is inaccessible, the field must also be inaccessible
            Subgraph catalog = subgraph("catalog", """
                type Query {
                    media: Media @inaccessible
                    movie: Movie
                }
                interface Media @inaccessible {
                    id: ID!
                }
                type Movie {
                    id: ID!
                }
                """);

            ValidationResult result = validate(catalog);
            assertValid(result);
        }
    }

    // ========================================================================
    // Invalid Cases - Interface has no implementations
    // ========================================================================

    @Nested
    class InvalidCases {

        @Test
        void interfaceUsedAsReturnTypeWithNoImplementation() {
            Subgraph catalog = subgraph("catalog", """
                type Query {
                    media: Media
                }
                interface Media {
                    id: ID!
                }
                type Movie {
                    id: ID!
                }
                """);

            ValidationResult result = validate(catalog);
            assertInvalidWithCoordinate(result, "Media");
        }

        @Test
        void interfaceUsedInListReturnTypeWithNoImplementation() {
            Subgraph catalog = subgraph("catalog", """
                type Query {
                    allMedia: [Media!]!
                }
                interface Media {
                    id: ID!
                }
                """);

            ValidationResult result = validate(catalog);
            assertInvalidWithCoordinate(result, "Media");
        }

        @Test
        void interfaceUsedOnFieldWithNoImplementation() {
            Subgraph catalog = subgraph("catalog", """
                type Query {
                    tvShow: TVShow
                }
                type TVShow {
                    id: ID!
                    relatedContent: [Media!]
                }
                interface Media {
                    id: ID!
                }
                """);

            ValidationResult result = validate(catalog);
            assertInvalidWithCoordinate(result, "Media");
        }

        @Test
        void multipleInterfacesWithNoImplementation() {
            Subgraph catalog = subgraph("catalog", """
                type Query {
                    media: Media
                    content: Content
                }
                interface Media {
                    id: ID!
                }
                interface Content {
                    title: String
                }
                """);

            ValidationResult result = validate(catalog);
            assertThat(result.errors())
                .hasSize(2)
                .anyMatch(d -> d.code().equals(CODE) && d.coordinate().equals("Media"))
                .anyMatch(d -> d.code().equals(CODE) && d.coordinate().equals("Content"));
        }
    }
}
