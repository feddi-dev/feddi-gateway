package dev.feddi.federation.engine.query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for Operation.parse() covering all 27 query patterns from the YAML test files.
 */
class OperationParseTest {

    @Nested
    @DisplayName("products_reviews schema queries")
    class ProductsReviewsQueries {

        @Test
        @DisplayName("basic_lookup: products with id, name, rating, reviews { text }")
        void parseBasicLookup() {
            String graphql = """
                {
                    products {
                        id
                        name
                        rating
                        reviews {
                            text
                        }
                    }
                }
                """;

            Operation result = Operation.parse(graphql);

            assertThat(result.rootType()).isEqualTo("Query");
            assertThat(result.selections()).hasSize(1);

            FieldSelection products = (FieldSelection) result.selections().get(0);
            assertThat(products.fieldName()).isEqualTo("products");
            assertThat(products.subSelections()).hasSize(4);
            assertThat(products.fieldSubSelections().get(0).fieldName()).isEqualTo("id");
            assertThat(products.fieldSubSelections().get(1).fieldName()).isEqualTo("name");
            assertThat(products.fieldSubSelections().get(2).fieldName()).isEqualTo("rating");

            FieldSelection reviews = products.fieldSubSelections().get(3);
            assertThat(reviews.fieldName()).isEqualTo("reviews");
            assertThat(reviews.subSelections()).hasSize(1);
            assertThat(reviews.fieldSubSelections().get(0).fieldName()).isEqualTo("text");
        }

        @Test
        @DisplayName("local_fields_only: products with id, name")
        void parseLocalFieldsOnly() {
            String graphql = "{ products { id name } }";

            Operation result = Operation.parse(graphql);

            assertThat(result.rootType()).isEqualTo("Query");
            FieldSelection products = (FieldSelection) result.selections().get(0);
            assertThat(products.fieldName()).isEqualTo("products");
            assertThat(products.fieldSubSelections()).extracting(FieldSelection::fieldName)
                .containsExactly("id", "name");
        }

        @Test
        @DisplayName("nested_review_fields: products with reviews { text, stars }")
        void parseNestedReviewFields() {
            String graphql = """
                {
                    products {
                        reviews {
                            text
                            stars
                        }
                    }
                }
                """;

            Operation result = Operation.parse(graphql);

            FieldSelection products = (FieldSelection) result.selections().get(0);
            FieldSelection reviews = products.fieldSubSelections().get(0);
            assertThat(reviews.fieldName()).isEqualTo("reviews");
            assertThat(reviews.fieldSubSelections()).extracting(FieldSelection::fieldName)
                .containsExactly("text", "stars");
        }

        @Test
        @DisplayName("remote_fields_only: products with rating")
        void parseRemoteFieldsOnly() {
            String graphql = "{ products { rating } }";

            Operation result = Operation.parse(graphql);

            FieldSelection products = (FieldSelection) result.selections().get(0);
            assertThat(products.subSelections()).hasSize(1);
            assertThat(products.fieldSubSelections().get(0).fieldName()).isEqualTo("rating");
        }
    }

    @Nested
    @DisplayName("single_subgraph schema queries")
    class SingleSubgraphQueries {

        @Test
        @DisplayName("all_fields: users with id, name, email, profile { bio, avatar }")
        void parseAllFields() {
            String graphql = """
                {
                    users {
                        id
                        name
                        email
                        profile {
                            bio
                            avatar
                        }
                    }
                }
                """;

            Operation result = Operation.parse(graphql);

            FieldSelection users = (FieldSelection) result.selections().get(0);
            assertThat(users.fieldName()).isEqualTo("users");
            assertThat(users.subSelections()).hasSize(4);

            FieldSelection profile = users.fieldSubSelections().get(3);
            assertThat(profile.fieldName()).isEqualTo("profile");
            assertThat(profile.fieldSubSelections()).extracting(FieldSelection::fieldName)
                .containsExactly("bio", "avatar");
        }

        @Test
        @DisplayName("partial_fields: users with name, email")
        void parsePartialFields() {
            String graphql = "{ users { name email } }";

            Operation result = Operation.parse(graphql);

            FieldSelection users = (FieldSelection) result.selections().get(0);
            assertThat(users.fieldSubSelections()).extracting(FieldSelection::fieldName)
                .containsExactly("name", "email");
        }

        @Test
        @DisplayName("nested_profile: users with profile { bio }")
        void parseNestedProfile() {
            String graphql = "{ users { profile { bio } } }";

            Operation result = Operation.parse(graphql);

            FieldSelection users = (FieldSelection) result.selections().get(0);
            FieldSelection profile = users.fieldSubSelections().get(0);
            assertThat(profile.fieldName()).isEqualTo("profile");
            assertThat(profile.fieldSubSelections().get(0).fieldName()).isEqualTo("bio");
        }
    }

    @Nested
    @DisplayName("users_orders_fulfillment schema queries")
    class UsersOrdersFulfillmentQueries {

        @Test
        @DisplayName("multi_hop: users with name, orders { total, trackingNumber }")
        void parseMultiHop() {
            String graphql = """
                {
                    users {
                        name
                        orders {
                            total
                            trackingNumber
                        }
                    }
                }
                """;

            Operation result = Operation.parse(graphql);

            FieldSelection users = (FieldSelection) result.selections().get(0);
            assertThat(users.subSelections()).hasSize(2);

            FieldSelection orders = users.fieldSubSelections().get(1);
            assertThat(orders.fieldName()).isEqualTo("orders");
            assertThat(orders.fieldSubSelections()).extracting(FieldSelection::fieldName)
                .containsExactly("total", "trackingNumber");
        }

        @Test
        @DisplayName("users_only: users with id, name")
        void parseUsersOnly() {
            String graphql = "{ users { id name } }";

            Operation result = Operation.parse(graphql);

            FieldSelection users = (FieldSelection) result.selections().get(0);
            assertThat(users.fieldSubSelections()).extracting(FieldSelection::fieldName)
                .containsExactly("id", "name");
        }

        @Test
        @DisplayName("users_and_orders: users with name, orders { total }")
        void parseUsersAndOrders() {
            String graphql = "{ users { name orders { total } } }";

            Operation result = Operation.parse(graphql);

            FieldSelection users = (FieldSelection) result.selections().get(0);
            assertThat(users.subSelections()).hasSize(2);
            assertThat(users.fieldSubSelections().get(0).fieldName()).isEqualTo("name");

            FieldSelection orders = users.fieldSubSelections().get(1);
            assertThat(orders.fieldSubSelections().get(0).fieldName()).isEqualTo("total");
        }

        @Test
        @DisplayName("order_status_only: users with orders { status }")
        void parseOrderStatusOnly() {
            String graphql = "{ users { orders { status } } }";

            Operation result = Operation.parse(graphql);

            FieldSelection users = (FieldSelection) result.selections().get(0);
            FieldSelection orders = users.fieldSubSelections().get(0);
            assertThat(orders.fieldSubSelections().get(0).fieldName()).isEqualTo("status");
        }
    }

    @Nested
    @DisplayName("cyclic_graph schema queries")
    class CyclicGraphQueries {

        @Test
        @DisplayName("both_branches: users with name, orders { total }, reviews { text }")
        void parseBothBranches() {
            String graphql = """
                {
                    users {
                        name
                        orders {
                            total
                        }
                        reviews {
                            text
                        }
                    }
                }
                """;

            Operation result = Operation.parse(graphql);

            FieldSelection users = (FieldSelection) result.selections().get(0);
            assertThat(users.subSelections()).hasSize(3);
            assertThat(users.fieldSubSelections().get(0).fieldName()).isEqualTo("name");
            assertThat(users.fieldSubSelections().get(1).fieldName()).isEqualTo("orders");
            assertThat(users.fieldSubSelections().get(2).fieldName()).isEqualTo("reviews");
        }

        @Test
        @DisplayName("orders_branch: users with orders { total }")
        void parseOrdersBranch() {
            String graphql = "{ users { orders { total } } }";

            Operation result = Operation.parse(graphql);

            FieldSelection users = (FieldSelection) result.selections().get(0);
            FieldSelection orders = users.fieldSubSelections().get(0);
            assertThat(orders.fieldSubSelections().get(0).fieldName()).isEqualTo("total");
        }

        @Test
        @DisplayName("reviews_branch: users with reviews { text }")
        void parseReviewsBranch() {
            String graphql = "{ users { reviews { text } } }";

            Operation result = Operation.parse(graphql);

            FieldSelection users = (FieldSelection) result.selections().get(0);
            FieldSelection reviews = users.fieldSubSelections().get(0);
            assertThat(reviews.fieldSubSelections().get(0).fieldName()).isEqualTo("text");
        }
    }

    @Nested
    @DisplayName("multiple_paths schema queries")
    class MultiplePathsQueries {

        @Test
        @DisplayName("price_field: products with name, price")
        void parsePriceField() {
            String graphql = "{ products { name price } }";

            Operation result = Operation.parse(graphql);

            FieldSelection products = (FieldSelection) result.selections().get(0);
            assertThat(products.fieldSubSelections()).extracting(FieldSelection::fieldName)
                .containsExactly("name", "price");
        }

        @Test
        @DisplayName("inventory_only: products with stock")
        void parseInventoryOnly() {
            String graphql = "{ products { stock } }";

            Operation result = Operation.parse(graphql);

            FieldSelection products = (FieldSelection) result.selections().get(0);
            assertThat(products.fieldSubSelections().get(0).fieldName()).isEqualTo("stock");
        }

        @Test
        @DisplayName("all_pricing_fields: products with price, currency")
        void parseAllPricingFields() {
            String graphql = "{ products { price currency } }";

            Operation result = Operation.parse(graphql);

            FieldSelection products = (FieldSelection) result.selections().get(0);
            assertThat(products.fieldSubSelections()).extracting(FieldSelection::fieldName)
                .containsExactly("price", "currency");
        }

        @Test
        @DisplayName("multiple_subgraphs: products with name, stock, price")
        void parseMultipleSubgraphs() {
            String graphql = "{ products { name stock price } }";

            Operation result = Operation.parse(graphql);

            FieldSelection products = (FieldSelection) result.selections().get(0);
            assertThat(products.fieldSubSelections()).extracting(FieldSelection::fieldName)
                .containsExactly("name", "stock", "price");
        }
    }

    @Nested
    @DisplayName("complex_nested schema queries")
    class ComplexNestedQueries {

        @Test
        @DisplayName("full_depth: organizations with deep nesting to assignee.username")
        void parseFullDepth() {
            String graphql = """
                {
                    organizations {
                        name
                        teams {
                            teamName
                            projects {
                                projectName
                                tasks {
                                    title
                                    status
                                    assignee {
                                        username
                                    }
                                }
                            }
                        }
                    }
                }
                """;

            Operation result = Operation.parse(graphql);

            FieldSelection orgs = (FieldSelection) result.selections().get(0);
            assertThat(orgs.fieldName()).isEqualTo("organizations");

            FieldSelection teams = orgs.fieldSubSelections().get(1);
            assertThat(teams.fieldName()).isEqualTo("teams");

            FieldSelection projects = teams.fieldSubSelections().get(1);
            assertThat(projects.fieldName()).isEqualTo("projects");

            FieldSelection tasks = projects.fieldSubSelections().get(1);
            assertThat(tasks.fieldName()).isEqualTo("tasks");

            FieldSelection assignee = tasks.fieldSubSelections().get(2);
            assertThat(assignee.fieldName()).isEqualTo("assignee");
            assertThat(assignee.fieldSubSelections().get(0).fieldName()).isEqualTo("username");
        }

        @Test
        @DisplayName("orgs_only: organizations with name, teams { teamName }")
        void parseOrgsOnly() {
            String graphql = """
                {
                    organizations {
                        name
                        teams {
                            teamName
                        }
                    }
                }
                """;

            Operation result = Operation.parse(graphql);

            FieldSelection orgs = (FieldSelection) result.selections().get(0);
            assertThat(orgs.subSelections()).hasSize(2);

            FieldSelection teams = orgs.fieldSubSelections().get(1);
            assertThat(teams.fieldSubSelections().get(0).fieldName()).isEqualTo("teamName");
        }

        @Test
        @DisplayName("orgs_to_projects: organizations through teams to projects")
        void parseOrgsToProjects() {
            String graphql = """
                {
                    organizations {
                        name
                        teams {
                            teamName
                            projects {
                                projectName
                                deadline
                            }
                        }
                    }
                }
                """;

            Operation result = Operation.parse(graphql);

            FieldSelection orgs = (FieldSelection) result.selections().get(0);
            FieldSelection teams = orgs.fieldSubSelections().get(1);
            FieldSelection projects = teams.fieldSubSelections().get(1);

            assertThat(projects.fieldName()).isEqualTo("projects");
            assertThat(projects.fieldSubSelections()).extracting(FieldSelection::fieldName)
                .containsExactly("projectName", "deadline");
        }

        @Test
        @DisplayName("tasks_only: minimal path to tasks { title }")
        void parseTasksOnly() {
            String graphql = """
                {
                    organizations {
                        teams {
                            projects {
                                tasks {
                                    title
                                }
                            }
                        }
                    }
                }
                """;

            Operation result = Operation.parse(graphql);

            FieldSelection orgs = (FieldSelection) result.selections().get(0);
            FieldSelection teams = orgs.fieldSubSelections().get(0);
            FieldSelection projects = teams.fieldSubSelections().get(0);
            FieldSelection tasks = projects.fieldSubSelections().get(0);

            assertThat(tasks.fieldName()).isEqualTo("tasks");
            assertThat(tasks.fieldSubSelections().get(0).fieldName()).isEqualTo("title");
        }
    }

    @Nested
    @DisplayName("field_requirements schema queries")
    class FieldRequirementsQueries {

        @Test
        @DisplayName("simple_key_lookup: products with name, price")
        void parseSimpleKeyLookup() {
            String graphql = "{ products { name price } }";

            Operation result = Operation.parse(graphql);

            FieldSelection products = (FieldSelection) result.selections().get(0);
            assertThat(products.fieldSubSelections()).extracting(FieldSelection::fieldName)
                .containsExactly("name", "price");
        }

        @Test
        @DisplayName("lookup_with_require: products with name, shippingCost")
        void parseLookupWithRequire() {
            String graphql = "{ products { name shippingCost } }";

            Operation result = Operation.parse(graphql);

            FieldSelection products = (FieldSelection) result.selections().get(0);
            assertThat(products.fieldSubSelections()).extracting(FieldSelection::fieldName)
                .containsExactly("name", "shippingCost");
        }

        @Test
        @DisplayName("multiple_lookups: products with name, price, shippingCost")
        void parseMultipleLookups() {
            String graphql = "{ products { name price shippingCost } }";

            Operation result = Operation.parse(graphql);

            FieldSelection products = (FieldSelection) result.selections().get(0);
            assertThat(products.fieldSubSelections()).extracting(FieldSelection::fieldName)
                .containsExactly("name", "price", "shippingCost");
        }

        @Test
        @DisplayName("all_shipping_fields: products with shippingCost, estimatedDays, carrier")
        void parseAllShippingFields() {
            String graphql = "{ products { shippingCost estimatedDays carrier } }";

            Operation result = Operation.parse(graphql);

            FieldSelection products = (FieldSelection) result.selections().get(0);
            assertThat(products.fieldSubSelections()).extracting(FieldSelection::fieldName)
                .containsExactly("shippingCost", "estimatedDays", "carrier");
        }

        @Test
        @DisplayName("catalog_only: products with id, name, weight, category")
        void parseCatalogOnly() {
            String graphql = "{ products { id name weight category } }";

            Operation result = Operation.parse(graphql);

            FieldSelection products = (FieldSelection) result.selections().get(0);
            assertThat(products.fieldSubSelections()).extracting(FieldSelection::fieldName)
                .containsExactly("id", "name", "weight", "category");
        }
    }

    @Nested
    @DisplayName("Edge cases and error handling")
    class EdgeCases {

        @Test
        @DisplayName("Named query operation")
        void parseNamedQuery() {
            String graphql = "query GetUsers { users { id } }";

            Operation result = Operation.parse(graphql);

            assertThat(result.rootType()).isEqualTo("Query");
            assertThat(((FieldSelection) result.selections().get(0)).fieldName()).isEqualTo("users");
        }

        @Test
        @DisplayName("Mutation operation")
        void parseMutation() {
            String graphql = "mutation { createUser { id } }";

            Operation result = Operation.parse(graphql);

            assertThat(result.rootType()).isEqualTo("Mutation");
        }

        @Test
        @DisplayName("Null query throws exception")
        void parseNullQuery() {
            assertThatThrownBy(() -> Operation.parse(null))
                .isInstanceOf(Operation.OperationParseException.class)
                .hasMessageContaining("null or blank");
        }

        @Test
        @DisplayName("Blank query throws exception")
        void parseBlankQuery() {
            assertThatThrownBy(() -> Operation.parse("   "))
                .isInstanceOf(Operation.OperationParseException.class)
                .hasMessageContaining("null or blank");
        }

        @Test
        @DisplayName("Invalid GraphQL throws exception")
        void parseInvalidGraphQL() {
            assertThatThrownBy(() -> Operation.parse("not valid graphql"))
                .isInstanceOf(Operation.OperationParseException.class);
        }
    }
}
