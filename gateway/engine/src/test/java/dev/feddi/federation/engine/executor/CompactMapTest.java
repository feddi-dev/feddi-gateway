package dev.feddi.federation.engine.executor;

import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompactMapTest {

    @Test
    void behavesLikeAnInsertionOrderedMap() {
        CompactMap map = new CompactMap(1);
        Map<String, Object> expected = new LinkedHashMap<>();
        for (int i = 0; i < 20; i++) {
            map.put("k" + i, i);
            expected.put("k" + i, i);
        }
        map.put(new String("k3"), "replaced");
        expected.put("k3", "replaced");
        map.put("nullable", null);
        expected.put("nullable", null);
        map.remove("k5");
        expected.remove("k5");

        assertThat(map).isEqualTo(expected).hasSameHashCodeAs(expected);
        assertThat(map.keySet()).containsExactlyElementsOf(expected.keySet());
        assertThat(map.containsKey("nullable")).isTrue();
        assertThat(map.get("nullable")).isNull();
        assertThat(map.get("missing")).isNull();
        assertThat(map.get(null)).isNull();
        assertThat(map.get(42)).isNull();
    }

    @Test
    void entriesCanBeChangedAndRemovedWhileIterating() {
        CompactMap map = new CompactMap(Map.of("a", 1));
        map.put("b", 2);
        map.put("c", 3);
        for (Iterator<Map.Entry<String, Object>> entries = map.entrySet().iterator(); entries.hasNext(); ) {
            Map.Entry<String, Object> entry = entries.next();
            if (entry.getKey().equals("b")) {
                entries.remove();
            } else {
                entry.setValue((Integer) entry.getValue() * 10);
            }
        }

        assertThat(map).containsExactly(Map.entry("a", 10), Map.entry("c", 30));
    }

    @Test
    void rejectsNullKeys() {
        assertThatThrownBy(() -> new CompactMap().put(null, 1)).isInstanceOf(NullPointerException.class);
    }
}
