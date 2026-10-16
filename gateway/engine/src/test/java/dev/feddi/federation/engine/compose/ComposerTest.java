package dev.feddi.federation.engine.compose;

import dev.feddi.federation.engine.compose.Composer.SubgraphInput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComposerTest {

    @Test
    void subgraphInputRequiresNameAndSdl() {
        assertThatThrownBy(() -> SubgraphInput.of(null, "type Query { a: Int }"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("name");
        assertThatThrownBy(() -> SubgraphInput.of(" ", "type Query { a: Int }"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("name");
        assertThatThrownBy(() -> SubgraphInput.of("a", null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sdl");
        assertThatThrownBy(() -> SubgraphInput.of("a", " "))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sdl");
    }

    @Test
    void composingParsedSubgraphsRequiresSkipParsing() {
        assertThatThrownBy(() -> new Composer().compose(List.of(), false))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
