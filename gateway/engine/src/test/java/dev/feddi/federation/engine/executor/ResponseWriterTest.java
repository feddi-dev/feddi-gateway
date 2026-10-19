package dev.feddi.federation.engine.executor;

import graphql.language.OperationDefinition;
import graphql.parser.Parser;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaGenerator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Fields that fragments select for some object types only. */
class ResponseWriterTest {

    private static final GraphQLSchema SCHEMA = SchemaGenerator.createdMockedSchema("""
        type Query { items: [Item] }
        union Item = Product | Review
        type Product { id: ID! name: String owner: User }
        type Review { id: ID! body: String owner: Team }
        type User { name: String! }
        type Team { title: String }
        """);

    @Test
    void leavesOutFieldsSelectedForOtherTypes() {
        String json = write("{ items { ... on Product { id name } ... on Review { body } } }", List.of(
            object("__typename", "Review", "id", "r1", "body", "good"),
            object("__typename", "Product", "id", "p1", "name", "Table", "body", "internal")));

        assertThat(json).isEqualTo("{\"data\":{\"items\":[{\"body\":\"good\"},{\"id\":\"p1\",\"name\":\"Table\"}]}}");
    }

    @Test
    void writesFieldsOfObjectsWithoutTypeAsTheyArePresent() {
        String json = write("{ items { ... on Product { id } ... on Review { body } } }", List.of(
            object("id", "p1", "body", "good")));

        assertThat(json).isEqualTo("{\"data\":{\"items\":[{\"id\":\"p1\",\"body\":\"good\"}]}}");
    }

    @Test
    void usesTheFieldTypeOfTheObjectsType() {
        // owner is a User (name non-null) below Product and a Team below Review
        String json = write("""
            { items { ... on Product { owner { name } } ... on Review { owner { title } } } }
            """, List.of(
            object("__typename", "Review", "owner", object("title", "Core", "name", "internal")),
            object("__typename", "Product", "owner", object("name", null, "title", "internal"))));

        assertThat(json)
            .contains("Cannot return null for non-nullable field User.name.")
            .endsWith("\"data\":{\"items\":[{\"owner\":{\"title\":\"Core\"}},{\"owner\":null}]}}");
    }

    private static String write(String query, List<Object> items) {
        OperationDefinition operation = Parser.parse(query).getDefinitionsOfType(OperationDefinition.class).get(0);
        ResponseWriter.Output output = ResponseWriter.write(ResponseShape.compile(SCHEMA, operation),
            object("items", items), List.of(), null);
        return new String(output.json(), StandardCharsets.UTF_8);
    }

    private static Map<String, Object> object(Object... keysAndValues) {
        CompactMap map = new CompactMap();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }
}
