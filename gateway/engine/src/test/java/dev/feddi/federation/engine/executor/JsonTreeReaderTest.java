package dev.feddi.federation.engine.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
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

        assertThat(read).isInstanceOf(CompactMap.class).isEqualTo(new ObjectMapper().readValue(JSON, Map.class));
    }

    @Test
    void readsJsonLines() throws Exception {
        List<Object> lines = JsonTreeReader.readSequence("{\"a\":1}\n{\"a\":2}\r\n".getBytes(StandardCharsets.UTF_8));

        assertThat(lines).containsExactly(Map.of("a", 1), Map.of("a", 2));
    }

    @Test
    void rejectsInvalidJson() {
        assertThatThrownBy(() -> JsonTreeReader.read("{\"a\":".getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> JsonTreeReader.read("{} {}".getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(java.io.IOException.class);
    }
}
