package dev.feddi.federation.engine.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.language.OperationDefinition;
import graphql.parser.Parser;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaGenerator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compares the one-pass {@link ResponseWriter} with completing the data as maps ({@link NonNullPropagation},
 * {@link ResponseFieldOrder}) and serializing them with Jackson, on random data: nulls also in non-null positions,
 * missing fields, fields in random order, strings as JSON text with escapes, and errors at random paths.
 */
class ResponseWriterRandomTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final GraphQLSchema SCHEMA = SchemaGenerator.createdMockedSchema("""
        type Query {
          product: Product
          products: [Product!]
          matrix: [[Int!]]!
          items: [Item]
          required: Product!
        }

        type Product {
          id: ID!
          name: String
          price: Int!
          tags: [String!]!
          related: [Product]
          reviews: [Review!]
        }

        type Review {
          id: ID!
          body: String
          rating: Int
          author: User!
        }

        type User {
          id: ID!
          name: String!
        }

        union Item = Product | Review
        """);

    /** A normalized operation (named fragments inlined), with aliases and inline fragments. */
    private static final OperationDefinition OPERATION = Parser.parse("""
        {
          product { id name price tags related { id name } reviews { id body author { id name } } }
          products { id price title: name reviews { rating author { name } } }
          matrix
          items { ... on Product { id name } ... on Review { id body } }
          required { id tags }
        }
        """).getDefinitionsOfType(OperationDefinition.class).get(0);

    private static final String[] STRINGS = {"plain", "", "a \\\"quoted\\\" b", "back\\\\slash", "line\\nbreak",
        "caf\\u00e9", "ä € 😀", "\\uD83D\\uDE00"};

    @Test
    void writesWhatCompletingTheMapsWouldGive() throws Exception {
        ResponseShape shape = ResponseShape.compile(SCHEMA, OPERATION);
        for (long seed = 0; seed < 2000; seed++) {
            Random random = new Random(seed);
            Map<String, Object> data = randomRoot(random);
            List<GraphQLError> errors = randomErrors(random, data);

            // Written first: completing the maps changes them in place
            ResponseWriter.Output written = ResponseWriter.write(shape, data, errors, null);

            ExecutionResult completed = NonNullPropagation.apply(SCHEMA, OPERATION, ExecutionResultImpl
                .newExecutionResult().data(RawString.decodeAll(data)).errors(errors).build());
            Map<String, Object> expected = new LinkedHashMap<>();
            if (!completed.getErrors().isEmpty()) {
                expected.put("errors", completed.getErrors().stream().map(GraphQLError::toSpecification).toList());
            }
            expected.put("data", completed.getData() == null ? null
                : ResponseFieldOrder.reorder(OPERATION.getSelectionSet(), completed.getData()));

            // Compared as parsed JSON, serialized again to compare the order of the fields too
            String actualJson = JSON.writeValueAsString(JSON.readValue(written.json(), Object.class));
            assertThat(actualJson).as("seed %d, written %s", seed, new String(written.json(), StandardCharsets.UTF_8))
                .isEqualTo(JSON.writeValueAsString(expected));
            assertThat(written.errorCount()).as("seed %d", seed).isEqualTo(completed.getErrors().size());
        }
    }

    /** Data with the selected fields only (internal fields are left out by the gateway either way). */
    private static Map<String, Object> randomRoot(Random random) {
        Map<String, Object> root = new LinkedHashMap<>();
        maybePut(random, root, "product", () -> {
            Map<String, Object> product = new LinkedHashMap<>();
            maybePut(random, product, "id", () -> randomString(random));
            maybePut(random, product, "name", () -> randomString(random));
            maybePut(random, product, "price", () -> random.nextInt(1000));
            maybePut(random, product, "tags", () -> randomList(random, () -> randomString(random)));
            maybePut(random, product, "related", () -> randomList(random, () -> object(random,
                Map.of("id", () -> randomString(random), "name", () -> randomString(random)))));
            maybePut(random, product, "reviews", () -> randomList(random, () -> object(random, Map.of(
                "id", () -> randomString(random),
                "body", () -> randomString(random),
                "author", () -> object(random,
                    Map.of("id", () -> randomString(random), "name", () -> randomString(random)))))));
            return shuffled(random, product);
        });
        maybePut(random, root, "products", () -> randomList(random, () -> object(random, Map.of(
            "id", () -> randomString(random),
            "price", () -> random.nextInt(1000),
            "title", () -> randomString(random),
            "reviews", () -> randomList(random, () -> object(random, Map.of(
                "rating", () -> random.nextInt(5),
                "author", () -> object(random, Map.of("name", () -> randomString(random))))))))));
        maybePut(random, root, "matrix", () -> randomList(random, () -> randomList(random, () -> random.nextInt(100))));
        maybePut(random, root, "items", () -> randomList(random, () -> random.nextBoolean()
            ? object(random, Map.of("id", () -> randomString(random), "name", () -> randomString(random)))
            : object(random, Map.of("id", () -> randomString(random), "body", () -> randomString(random)))));
        maybePut(random, root, "required", () -> object(random, Map.of(
            "id", () -> randomString(random),
            "tags", () -> randomList(random, () -> randomString(random)))));
        return shuffled(random, root);
    }

    /** An object with some of the fields, in random order. */
    private static Map<String, Object> object(Random random, Map<String, java.util.function.Supplier<Object>> fields) {
        Map<String, Object> object = new LinkedHashMap<>();
        new java.util.TreeMap<>(fields).forEach((key, value) -> maybePut(random, object, key, value));
        return shuffled(random, object);
    }

    /** Usually puts the value, sometimes null, sometimes nothing. */
    private static void maybePut(Random random, Map<String, Object> object, String key,
                                 java.util.function.Supplier<Object> value) {
        int choice = random.nextInt(20);
        if (choice == 0) {
            return;
        }
        object.put(key, choice == 1 ? null : value.get());
    }

    private static List<Object> randomList(Random random, java.util.function.Supplier<Object> item) {
        int size = random.nextInt(4);
        List<Object> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(random.nextInt(25) == 0 ? null : item.get());
        }
        return list;
    }

    /** A string as a subgraph response holds it: JSON text, sometimes with escapes. */
    private static Object randomString(Random random) {
        String text = "\"" + STRINGS[random.nextInt(STRINGS.length)] + "\"";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return random.nextBoolean() ? new RawString(bytes, 0, bytes.length)
            : JsonTreeReader.decodeString(bytes, 0, bytes.length);
    }

    private static Map<String, Object> shuffled(Random random, Map<String, Object> object) {
        List<String> keys = new ArrayList<>(object.keySet());
        Collections.shuffle(keys, random);
        Map<String, Object> shuffled = random.nextBoolean() ? new CompactMap() : new LinkedHashMap<>();
        keys.forEach(key -> shuffled.put(key, object.get(key)));
        return shuffled;
    }

    /** Sometimes an error at the path of a field, as a subgraph reports it. */
    private static List<GraphQLError> randomErrors(Random random, Map<String, Object> data) {
        List<GraphQLError> errors = new ArrayList<>();
        if (random.nextInt(3) == 0) {
            List<Object> path = random.nextBoolean() ? List.of("product", "id") : List.of("products", 0, "price");
            errors.add(GraphqlErrorBuilder.newError().message("subgraph error").path(path).build());
        }
        return errors;
    }
}
