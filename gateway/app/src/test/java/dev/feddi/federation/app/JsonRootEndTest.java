package dev.feddi.federation.app;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class JsonRootEndTest {

    @Test
    void endsAfterTheRootObjectAcrossChunks() {
        var json = new DefaultSubgraphClient.JsonRootEnd();

        assertThat(json.append(chunk("{\"data\":{\"a\":\"}\\\"{\""))).isFalse();
        assertThat(json.append(chunk(",\"b\":[1,{}]}"))).isFalse();
        assertThat(json.append(chunk("}"))).isTrue();
        assertThat(new String(json.bytes(), StandardCharsets.UTF_8))
            .isEqualTo("{\"data\":{\"a\":\"}\\\"{\",\"b\":[1,{}]}}");
    }

    @Test
    void doesNotEndWithoutARoot() {
        var json = new DefaultSubgraphClient.JsonRootEnd();

        assertThat(json.append(chunk("  \"text\" 1 "))).isFalse();
        assertThat(json.length()).isEqualTo(11);
    }

    @Test
    void endsAfterARootArray() {
        var json = new DefaultSubgraphClient.JsonRootEnd();

        assertThat(json.append(chunk("[\"]\"]"))).isTrue();
    }

    private static ByteBuf chunk(String text) {
        return Unpooled.copiedBuffer(text, StandardCharsets.UTF_8);
    }
}
