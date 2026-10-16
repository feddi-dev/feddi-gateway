package dev.feddi.federation.engine.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.feddi.federation.engine.IntrospectionFields;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonTreeReaderTest {

    private static final String JSON = """
        {"data":{"products":[{"upc":"1","price":899,"weight":10.5,"big":12345678901,"huge":123456789012345678901234,
        "inStock":true,"used":false,"note":null,"name":"Table \\"Oak\\" \\u00e4"}]},"errors":[]}
        """;

    @Test
    void readsLikeJackson() throws Exception {
        Object read = JsonTreeReader.read(JSON.getBytes(StandardCharsets.UTF_8));

        assertThat(read).isInstanceOf(CompactMap.class);
        assertThat(RawString.decodeAll(read)).isEqualTo(new ObjectMapper().readValue(JSON, Map.class));
    }

    @Test
    void readsJsonLines() throws Exception {
        List<Object> lines = JsonTreeReader.readSequence("{\"a\":1}\n{\"a\":2}\r\n".getBytes(StandardCharsets.UTF_8));

        assertThat(lines).containsExactly(Map.of("a", 1), Map.of("a", 2));
    }

    @Test
    void keepsStringsAsTheirJsonTextExceptTypeNames() throws Exception {
        byte[] json = ("{\"" + IntrospectionFields.TYPENAME + "\":\"Product\",\"name\":\"a \\\"b\\\"\"}")
            .getBytes(StandardCharsets.UTF_8);
        Map<?, ?> object = (Map<?, ?>) JsonTreeReader.read(json);

        assertThat(object.get(IntrospectionFields.TYPENAME)).isEqualTo("Product");
        assertThat(object.get("name")).isInstanceOf(RawString.class).hasToString("a \"b\"");
        JsonBuffer buffer = new JsonBuffer(16);
        buffer.writeValue(object);
        assertThat(new String(buffer.toByteArray(), StandardCharsets.UTF_8)).isEqualTo(new String(json, StandardCharsets.UTF_8));
    }

    @Test
    void rawStringsAreEqualByValue() throws Exception {
        Object plain = ((Map<?, ?>) JsonTreeReader.read("{\"a\":\"\u00e4\"}".getBytes(StandardCharsets.UTF_8))).get("a");
        Object escaped = ((Map<?, ?>) JsonTreeReader.read("{\"a\":\"ä\"}".getBytes(StandardCharsets.UTF_8))).get("a");

        assertThat(plain).isEqualTo(escaped).hasSameHashCodeAs(escaped).hasToString("ä");
    }

    @Test
    void readsLongStringsWithEscapesAtEveryOffset() throws Exception {
        ObjectMapper jackson = new ObjectMapper();
        for (int offset = 0; offset < 20; offset++) {
            String prefix = "x".repeat(offset);
            for (String special : List.of("\\\"", "\\\\", "\\n", "ä€", "\\u0041")) {
                String json = "[\"" + prefix + special + "y".repeat(17) + "\",\"" + "z".repeat(offset) + "\"]";
                Object read = RawString.decodeAll(JsonTreeReader.read(json.getBytes(StandardCharsets.UTF_8)));
                assertThat(read).as(json).isEqualTo(jackson.readValue(json, List.class));
            }
        }
    }

    @Test
    void rejectsControlCharactersInStrings() {
        String json = "[\"" + "x".repeat(11) + "\u0001" + "\"]";
        assertThatThrownBy(() -> JsonTreeReader.read(json.getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void rejectsInvalidJson() {
        assertThatThrownBy(() -> JsonTreeReader.read("{\"a\":".getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> JsonTreeReader.read("{} {}".getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(java.io.IOException.class);
    }
}
