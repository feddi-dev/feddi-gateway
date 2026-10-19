package dev.feddi.federation.engine.executor;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link Executor#deepMerge} into maps from subgraph responses ({@link CompactMap}) and into other maps. */
class DeepMergeTest {

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void mergesNestedObjectsAndListsOfTheSameSize(boolean compact) {
        Map<String, Object> target = map(compact,
            "product", map(compact, "id", "1"),
            "reviews", list(map(compact, "id", "r1"), "text", map(compact, "id", "r3")),
            "tags", list("a"),
            "name", "Table",
            "price", 10);
        Map<String, Object> source = map(false,
            "product", map(false, "name", "Chair"),
            "reviews", list(map(false, "body", "good"), map(false, "body", "ignored"), "text"),
            "tags", list("b", "c"),
            "name", null,
            "price", 12,
            "added", null);

        Executor.deepMerge(target, source);

        assertThat(target).isEqualTo(map(false,
            "product", map(false, "id", "1", "name", "Chair"),
            "reviews", list(map(false, "id", "r1", "body", "good"), "text", map(false, "id", "r3")),
            "tags", list("b", "c"),
            "name", "Table",
            "price", 12,
            "added", null));
    }

    private static Map<String, Object> map(boolean compact, Object... keysAndValues) {
        Map<String, Object> map = compact ? new CompactMap() : new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    private static List<Object> list(Object... items) {
        return new ArrayList<>(Arrays.asList(items));
    }
}
