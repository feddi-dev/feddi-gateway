package dev.feddi.federation.engine.executor;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Which lookup results positions of other steps can share. */
class ResultCopiesTest {

    @Test
    void sharesLookupObjectsWithLeavesOnly() {
        Map<String, Object> data = Map.of("product", Map.of("name", "Table", "price", 899));
        Map<String, Object> nullLookup = new java.util.HashMap<>();
        nullLookup.put("product", null);

        assertThat(ResultCopies.forMerge(data)).isSameAs(data);
        assertThat(ResultCopies.forMerge(nullLookup)).isSameAs(nullLookup);
    }

    @Test
    void copiesLookupsWithObjectsOrLists() {
        // The merge links the nested objects and lists into the response, where later steps write into them
        Map<String, Object> nested = Map.of("product", Map.of("dimensions", Map.of("size", 1)));
        Map<String, Object> list = Map.of("product", Map.of("reviews", List.of()));
        Map<String, Object> listLookup = Map.of("products", List.of(Map.of("name", "Table")));

        for (Map<String, Object> data : List.of(nested, list, listLookup)) {
            Object copy = ResultCopies.forMerge(data);
            assertThat(copy).isNotSameAs(data).isEqualTo(data);
        }
    }
}
