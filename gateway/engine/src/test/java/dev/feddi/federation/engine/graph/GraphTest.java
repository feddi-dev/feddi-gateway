package dev.feddi.federation.engine.graph;

import dev.feddi.federation.engine.parser.FieldSelectionMap.Path;
import dev.feddi.federation.engine.parser.FieldSelectionMap.SelectedValue;
import graphql.language.TypeName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphTest {

    @Test
    void interfaceFieldRequiresWhenAnImplementationFieldDoes() {
        Node query = new Node("Query", "a");
        Node book = new Node("Book", "a");
        Graph graph = Graph.builder()
            .addNode(query).addNode(book).root(query)
            .addTypeImplementsInterfaces("Book", Set.of("Media"))
            .addRequirements(book, List.of(
                new Requirement("size", new SelectedValue(Path.of("size")), new TypeName("Int"), "price")))
            .build();

        assertThat(graph.fieldHasRequire("Media", "a", "price")).isTrue();
        assertThat(graph.fieldHasRequire("Media", "a", "title")).isFalse();
        assertThat(graph.fieldHasRequire("Media", "b", "price")).isFalse();
    }

    @Test
    void buildRequiresARootNode() {
        assertThatThrownBy(() -> Graph.builder().addNode("Query", "a").build())
            .isInstanceOf(IllegalStateException.class);
    }
}
