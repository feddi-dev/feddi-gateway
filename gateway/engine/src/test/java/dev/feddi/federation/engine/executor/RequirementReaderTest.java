package dev.feddi.federation.engine.executor;

import dev.feddi.federation.engine.parser.FieldSelectionMapParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Which entities can provide a lookup's requirements, for requirements that are not plain key paths. */
class RequirementReaderTest {

    private static final RequirementReader READER = new RequirementReader(Map.of());

    private static final List<Map<String, Object>> CONTEXTS = List.of(
        Map.of("id", "1"),
        Map.of("sku", "2"),
        Map.of("name", "3"),
        Map.of("dimension", Map.of("size", 1)));

    @Test
    void keepsContextsWithOneOfTheAlternatives() throws Exception {
        var requirements = Map.of("key", FieldSelectionMapParser.parseFieldSelectionMap("id | sku"));

        assertThat(READER.filterContextsForRequirements(CONTEXTS, requirements))
            .containsExactly(CONTEXTS.get(0), CONTEXTS.get(1));
    }

    @Test
    void keepsContextsWithEveryRequirement() throws Exception {
        var requirements = Map.of(
            "key", FieldSelectionMapParser.parseFieldSelectionMap("id | sku"),
            "size", FieldSelectionMapParser.parseFieldSelectionMap("dimension.size | name"));

        assertThat(READER.filterContextsForRequirements(CONTEXTS, requirements)).isEmpty();
        assertThat(READER.filterContextsForRequirements(List.of(Map.of("id", "1", "name", "n")), requirements))
            .hasSize(1);
    }

    @Test
    void noContextsOrNoRequirements() throws Exception {
        assertThat(READER.filterContextsForRequirements(List.of(), Map.of())).isEmpty();
        assertThat(READER.filterContextsForRequirements(CONTEXTS, Map.of())).isEqualTo(CONTEXTS);
    }
}
