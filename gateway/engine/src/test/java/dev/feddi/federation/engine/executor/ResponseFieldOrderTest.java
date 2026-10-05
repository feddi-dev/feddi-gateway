package dev.feddi.federation.engine.executor;

import graphql.language.OperationDefinition;
import graphql.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseFieldOrderTest {

    @Test
    void ordersNestedObjectsAndListItemsBySelection() {
        var data = object("users", List.of(
            object("name", "Ann", "id", "1", "address", object("zip", "10115", "city", "Berlin")),
            object("address", null, "id", "2", "name", "Bob")));

        var reordered = reorder("{ users { id name address { city zip } } }", data);

        List<Map<String, Object>> users = list(map(reordered).get("users"));
        assertThat(users.get(0).keySet()).containsExactly("id", "name", "address");
        assertThat(map(users.get(0).get("address")).keySet()).containsExactly("city", "zip");
        assertThat(users.get(1).keySet()).containsExactly("id", "name", "address");
        assertThat(users.get(1).get("address")).isNull();
    }

    @Test
    void usesResponseKeysOfAliases() {
        var reordered = reorder("{ b: product { y: name x: id } a: product { id } }",
            object("a", object("id", "1"), "b", object("x", "1", "y", "Table")));

        assertThat(map(reordered).keySet()).containsExactly("b", "a");
        assertThat(map(map(reordered).get("b")).keySet()).containsExactly("y", "x");
    }

    @Test
    void fieldsInInlineFragmentsCountAtTheirFirstOccurrence() {
        var reordered = reorder("{ node { id ... on User { name } ... on Admin { name level } } }",
            object("node", object("level", 3, "name", "Ann", "id", "1")));

        assertThat(map(map(reordered).get("node")).keySet()).containsExactly("id", "name", "level");
    }

    @Test
    void keepsUnselectedKeysAtTheEnd() {
        var reordered = reorder("{ product { name id } }",
            object("product", object("extra", 1, "id", "1", "name", "Table")));

        assertThat(map(map(reordered).get("product")).keySet()).containsExactly("name", "id", "extra");
    }

    private static Object reorder(String query, Object data) {
        var operation = Parser.parse(query).getDefinitionsOfType(OperationDefinition.class).get(0);
        return ResponseFieldOrder.reorder(operation.getSelectionSet(), data);
    }

    /** An ordered map from alternating keys and values (values may be null). */
    private static Map<String, Object> object(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        List<Object> items = Arrays.asList(keysAndValues);
        for (int i = 0; i < items.size(); i += 2) {
            map.put((String) items.get(i), items.get(i + 1));
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
