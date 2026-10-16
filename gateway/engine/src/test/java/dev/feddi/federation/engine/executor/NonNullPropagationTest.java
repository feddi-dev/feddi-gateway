package dev.feddi.federation.engine.executor;

import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.language.OperationDefinition;
import graphql.parser.Parser;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaGenerator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NonNullPropagationTest {

    private static final GraphQLSchema SCHEMA = SchemaGenerator.createdMockedSchema("""
            type Query {
              product: Product
              requiredProduct: Product!
              products: [Product!]
              items: [Item]
            }

            type Product {
              id: ID!
              name: String
              tags: [String!]!
            }

            union Item = Product | Note

            type Note {
              text: String!
            }
            """);

    @Test
    void nullInNonNullFieldNullsTheNullableParentWithAnError() {
        ExecutionResult result = propagate("{ product { id name } }", Map.of("product", object("id", null, "name", "A")));

        assertThat(result.<Map<String, Object>>getData()).containsEntry("product", null);
        assertThat(result.getErrors()).singleElement().satisfies(error -> {
            assertThat(error.getMessage()).isEqualTo("Cannot return null for non-nullable field Product.id.");
            assertThat(error.getPath()).containsExactly("product", "id");
        });
    }

    @Test
    void nullPropagatesThroughNonNullFieldsToTheRoot() {
        ExecutionResult result = propagate("{ requiredProduct { id } }", Map.of("requiredProduct", object("id", null)));

        assertThat(result.<Map<String, Object>>getData()).isNull();
        assertThat(result.getErrors()).singleElement()
            .satisfies(error -> assertThat(error.getPath()).containsExactly("requiredProduct", "id"));
    }

    @Test
    void nullItemOfNonNullListNullsTheList() {
        ExecutionResult result = propagate("{ products { id } }",
            Map.of("products", Arrays.asList(object("id", "1"), null)));

        assertThat(result.<Map<String, Object>>getData()).containsEntry("products", null);
        assertThat(result.getErrors()).singleElement()
            .satisfies(error -> assertThat(error.getPath()).containsExactly("products", 1));
    }

    @Test
    void fieldsOfUnionMembersAreCheckedThroughTheirFragment() {
        ExecutionResult result = propagate("{ items { ... on Note { text } ... on Product { id } } }",
            Map.of("items", List.of(object("text", null), object("id", "1"))));

        assertThat(result.<Map<String, Object>>getData().get("items")).isEqualTo(Arrays.asList(null, object("id", "1")));
        assertThat(result.getErrors()).singleElement()
            .satisfies(error -> assertThat(error.getPath()).containsExactly("items", 0, "text"));
    }

    @Test
    void existingErrorAtOrBelowThePathIsNotRepeated() {
        GraphQLError subgraphError = GraphqlErrorBuilder.newError().message("boom").path(List.of("product", "id"))
            .build();
        ExecutionResult result = propagate("{ product { id } }", Map.of("product", object("id", null)),
            List.of(subgraphError));

        assertThat(result.<Map<String, Object>>getData()).containsEntry("product", null);
        assertThat(result.getErrors()).containsExactly(subgraphError);
    }

    @Test
    void validResponseIsReturnedUnchanged() {
        ExecutionResult input = ExecutionResultImpl.newExecutionResult()
            .data(mutable(Map.of("product", object("id", "1", "tags", List.of("a"))))).build();

        assertThat(NonNullPropagation.apply(SCHEMA, operation("{ product { id tags } }"), input)).isSameAs(input);
    }

    @Test
    void absentFieldsAreNotChecked() {
        ExecutionResult result = propagate("{ product { id tags } }", Map.of("product", object("id", "1")));

        assertThat(result.getErrors()).isEmpty();
    }

    private static ExecutionResult propagate(String query, Map<String, Object> data) {
        return propagate(query, data, List.of());
    }

    private static ExecutionResult propagate(String query, Map<String, Object> data, List<GraphQLError> errors) {
        ExecutionResult input = ExecutionResultImpl.newExecutionResult().data(mutable(data)).errors(errors).build();
        return NonNullPropagation.apply(SCHEMA, operation(query), input);
    }

    private static OperationDefinition operation(String query) {
        return Parser.parse(query).getDefinitionsOfType(OperationDefinition.class).get(0);
    }

    /** An object with keys and values in pairs (values may be null). */
    private static Map<String, Object> object(Object... keysAndValues) {
        Map<String, Object> object = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            object.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return object;
    }

    /** A deep copy with mutable maps and lists, as the executor produces them. */
    @SuppressWarnings("unchecked")
    private static <T> T mutable(T value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put((String) key, mutable(item)));
            return (T) copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            list.forEach(item -> copy.add(mutable(item)));
            return (T) copy;
        }
        return value;
    }
}
