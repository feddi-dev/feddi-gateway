package dev.feddi.federation.engine.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonBufferTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void writesStringsLikeJackson() throws Exception {
        for (String value : List.of("plain", "", "quote \" backslash \\ slash /", "tab\tnew\nline\r\u0001\u001f",
            "äöü ß", "€ 中文", "emoji 😀")) {
            assertThat(written(value)).as(value).isEqualTo(JSON.writeValueAsString(value));
        }
    }

    @Test
    void writesValuesLikeJackson() throws Exception {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("int", 1);
        object.put("long", 1L << 40);
        object.put("double", 2.5);
        object.put("decimal", new BigDecimal("12.30"));
        object.put("bool", true);
        object.put("null", null);
        object.put("list", Arrays.asList("a", null, Map.of("k", false)));

        assertThat(written(object)).isEqualTo(JSON.writeValueAsString(object));
    }

    @Test
    void resetDiscardsWrittenBytes() {
        JsonBuffer buffer = new JsonBuffer(1);
        buffer.writeString("keep");
        int position = buffer.position();
        buffer.writeString("discard");
        buffer.reset(position);
        buffer.writeNull();

        assertThat(new String(buffer.toByteArray(), StandardCharsets.UTF_8)).isEqualTo("\"keep\"null");
    }

    private static String written(Object value) {
        JsonBuffer buffer = new JsonBuffer(1);
        buffer.writeValue(value);
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }
}
