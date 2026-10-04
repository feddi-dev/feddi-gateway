package dev.feddi.federation.engine.executor;

import graphql.language.AstPrinter;
import graphql.language.OperationDefinition;
import graphql.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OperationTextsTest {

    private static OperationDefinition operation(String query) {
        return Parser.parse(query).getDefinitionsOfType(OperationDefinition.class).get(0);
    }

    @Test
    void printsLikeAstPrinterAndCachesPerInstance() {
        var op = operation("query ($id: ID!) { user(id: $id) { name } }");

        String compact = OperationTexts.compact(op);
        String pretty = OperationTexts.pretty(op);

        assertThat(compact).isEqualTo(AstPrinter.printAstCompact(op));
        assertThat(pretty).isEqualTo(AstPrinter.printAst(op));
        assertThat(OperationTexts.compact(op)).isSameAs(compact);
        assertThat(OperationTexts.pretty(op)).isSameAs(pretty);
    }

    @Test
    void staysBounded() {
        // Every parse creates a new operation instance, i.e. a new cache entry.
        for (int i = 0; i < OperationTexts.MAX_ENTRIES + 10; i++) {
            OperationTexts.compact(operation("{ a }"));
        }

        assertThat(OperationTexts.size()).isLessThanOrEqualTo(2 * OperationTexts.MAX_ENTRIES);
        assertThat(OperationTexts.compact(operation("{ a }"))).isEqualTo("{a}");
    }

    @Test
    void aliasTemplatesAreReusedPerOperationAndSize() {
        var op = operation("query ($id: ID!) { user(id: $id) { name } }");

        var first = AliasBatch.create(op, List.of(Map.of("id", "1"), Map.of("id", "2")), 64);
        var second = AliasBatch.create(op, List.of(Map.of("id", "3"), Map.of("id", "4")), 64);
        var larger = AliasBatch.create(op, List.of(Map.of("id", "1"), Map.of("id", "2"), Map.of("id", "3")), 64);

        assertThat(second.operation()).isSameAs(first.operation());
        assertThat(second.variables()).containsEntry("id_b0", "3").containsEntry("id_b1", "4");
        assertThat(larger.operation()).isNotSameAs(first.operation());
    }
}
