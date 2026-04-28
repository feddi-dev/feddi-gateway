package dev.feddi.federation.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ResponseFieldValidator which ensures responses only contain requested fields.
 */
class ResponseFieldValidatorTest {

    @Test
    void validResponse_noErrors() {
        String query = "{ products { id name } }";
        Map<String, Object> response = Map.of(
            "products", List.of(
                Map.of("id", "p1", "name", "Laptop"),
                Map.of("id", "p2", "name", "Phone")
            )
        );

        List<String> errors = ResponseFieldValidator.validate(query, response);

        assertThat(errors).isEmpty();
    }

    @Test
    void extraFieldAtRoot_reportsError() {
        String query = "{ products { id } }";
        Map<String, Object> response = Map.of(
            "products", List.of(Map.of("id", "p1")),
            "extraField", "should not be here"
        );

        List<String> errors = ResponseFieldValidator.validate(query, response);

        assertThat(errors)
            .hasSize(1)
            .first()
            .isEqualTo("Unexpected field in response: extraField");
    }

    @Test
    void extraFieldInNestedObject_reportsError() {
        String query = "{ products { id name } }";
        Map<String, Object> response = Map.of(
            "products", List.of(
                Map.of("id", "p1", "name", "Laptop", "price", 999)  // price not requested
            )
        );

        List<String> errors = ResponseFieldValidator.validate(query, response);

        assertThat(errors)
            .hasSize(1)
            .first()
            .isEqualTo("Unexpected field in response: products[0].price");
    }

    @Test
    void extraFieldInDeeplyNestedObject_reportsError() {
        String query = "{ users { profile { name } } }";
        Map<String, Object> response = Map.of(
            "users", List.of(
                Map.of("profile", Map.of("name", "Alice", "age", 30))  // age not requested
            )
        );

        List<String> errors = ResponseFieldValidator.validate(query, response);

        assertThat(errors)
            .hasSize(1)
            .first()
            .isEqualTo("Unexpected field in response: users[0].profile.age");
    }

    @Test
    void typenameAlwaysAllowed() {
        String query = "{ products { id } }";
        Map<String, Object> response = Map.of(
            "__typename", "Query",
            "products", List.of(
                Map.of("id", "p1", "__typename", "Product")
            )
        );

        List<String> errors = ResponseFieldValidator.validate(query, response);

        assertThat(errors).isEmpty();
    }

    @Test
    void inlineFragment_fieldsAllowed() {
        String query = """
            {
              node {
                id
                ... on Product {
                  name
                  price
                }
              }
            }
            """;
        Map<String, Object> response = Map.of(
            "node", Map.of(
                "id", "1",
                "name", "Laptop",
                "price", 999
            )
        );

        List<String> errors = ResponseFieldValidator.validate(query, response);

        assertThat(errors).isEmpty();
    }

    @Test
    void inlineFragment_extraFieldInFragment_reportsError() {
        String query = """
            {
              node {
                id
                ... on Product {
                  name
                }
              }
            }
            """;
        Map<String, Object> response = Map.of(
            "node", Map.of(
                "id", "1",
                "name", "Laptop",
                "price", 999  // price not in fragment
            )
        );

        List<String> errors = ResponseFieldValidator.validate(query, response);

        assertThat(errors)
            .hasSize(1)
            .first()
            .isEqualTo("Unexpected field in response: node.price");
    }

    @Test
    void multipleInlineFragments_allFieldsAllowed() {
        String query = """
            {
              search {
                ... on Product {
                  name
                  price
                }
                ... on User {
                  username
                  email
                }
              }
            }
            """;
        Map<String, Object> response = Map.of(
            "search", List.of(
                Map.of("name", "Laptop", "price", 999),
                Map.of("username", "alice", "email", "alice@example.com")
            )
        );

        List<String> errors = ResponseFieldValidator.validate(query, response);

        assertThat(errors).isEmpty();
    }

    @Test
    void nullResponse_noErrors() {
        String query = "{ product { id name } }";
        Map<String, Object> response = null;

        List<String> errors = ResponseFieldValidator.validate(query, response);

        assertThat(errors).isEmpty();
    }

    @Test
    void nullNestedValue_noErrors() {
        String query = "{ product { id author { name } } }";
        Map<String, Object> response = Map.of(
            "product", Map.of(
                "id", "p1",
                "author", Map.of()  // empty map is fine
            )
        );

        List<String> errors = ResponseFieldValidator.validate(query, response);

        assertThat(errors).isEmpty();
    }

    @Test
    void multipleErrors_allReported() {
        String query = "{ product { id } }";
        Map<String, Object> response = Map.of(
            "product", Map.of("id", "p1", "name", "Laptop", "price", 999),
            "extraRoot", "value"
        );

        List<String> errors = ResponseFieldValidator.validate(query, response);

        assertThat(errors)
            .hasSize(3)
            .contains("Unexpected field in response: extraRoot")
            .contains("Unexpected field in response: product.name")
            .contains("Unexpected field in response: product.price");
    }

    @Test
    void emptyListResponse_noErrors() {
        String query = "{ products { id name } }";
        Map<String, Object> response = Map.of(
            "products", List.of()
        );

        List<String> errors = ResponseFieldValidator.validate(query, response);

        assertThat(errors).isEmpty();
    }

    @Test
    void scalarListResponse_noValidation() {
        String query = "{ tags }";
        Map<String, Object> response = Map.of(
            "tags", List.of("java", "graphql", "federation")
        );

        List<String> errors = ResponseFieldValidator.validate(query, response);

        assertThat(errors).isEmpty();
    }
}
