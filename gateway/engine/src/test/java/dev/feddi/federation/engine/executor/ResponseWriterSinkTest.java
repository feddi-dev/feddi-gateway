package dev.feddi.federation.engine.executor;

import graphql.language.OperationDefinition;
import graphql.parser.Parser;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaGenerator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Responses written into the thread's scratch buffer and handed to a sink. */
class ResponseWriterSinkTest {

    private static final GraphQLSchema SCHEMA = SchemaGenerator.createdMockedSchema("""
        type Query { names: [String] }
        """);

    private static final ResponseShape SHAPE = ResponseShape.compile(SCHEMA,
        Parser.parse("{ names }").getDefinitionsOfType(OperationDefinition.class).getFirst());

    @Test
    void writesTheSameJsonAsAnOutput() {
        Map<String, Object> data = names("a", "b");

        String viaSink = ResponseWriter.write(SHAPE, data, List.of(), null, ResponseWriterSinkTest::string);

        assertThat(viaSink).isEqualTo("{\"data\":{\"names\":[\"a\",\"b\"]}}")
            .isEqualTo(new String(ResponseWriter.write(SHAPE, data, List.of(), null).json(), StandardCharsets.UTF_8));
    }

    @Test
    void reusesTheBufferOfTheThread() {
        byte[] first = ResponseWriter.write(SHAPE, names("a"), List.of(), null, (bytes, length, errors) -> bytes);
        byte[] second = ResponseWriter.write(SHAPE, names("b"), List.of(), null, (bytes, length, errors) -> bytes);

        assertThat(second).isSameAs(first);
    }

    @Test
    void outputsHaveArraysOfTheirOwn() {
        ResponseWriter.Output first = ResponseWriter.write(SHAPE, names("a"), List.of(), null);
        ResponseWriter.write(SHAPE, names("b"), List.of(), null);

        assertThat(new String(first.json(), StandardCharsets.UTF_8)).isEqualTo("{\"data\":{\"names\":[\"a\"]}}");
    }

    @Test
    void sinkThatWritesAnotherResponseGetsAnotherBuffer() {
        var inner = new AtomicReference<String>();
        // The thread's scratch buffer exists from here on
        ResponseWriter.write(SHAPE, names("warm-up"), List.of(), null, ResponseWriterSinkTest::string);

        String outer = ResponseWriter.write(SHAPE, names("outer"), List.of(), null, (bytes, length, errors) -> {
            inner.set(ResponseWriter.write(SHAPE, names("inner"), List.of(), null, ResponseWriterSinkTest::string));
            return string(bytes, length, errors);
        });

        assertThat(outer).isEqualTo("{\"data\":{\"names\":[\"outer\"]}}");
        assertThat(inner.get()).isEqualTo("{\"data\":{\"names\":[\"inner\"]}}");
    }

    @Test
    void doesNotKeepLargeBuffers() {
        byte[] large = ResponseWriter.write(SHAPE, names(Collections.nCopies(2_000, "x".repeat(1_000))), List.of(),
            null, (bytes, length, errors) -> bytes);
        byte[] next = ResponseWriter.write(SHAPE, names("a"), List.of(), null, (bytes, length, errors) -> bytes);

        assertThat(next).isNotSameAs(large);
    }

    @Test
    void virtualThreadsGetBuffersOfTheirOwn() throws Exception {
        var arrays = new ArrayList<byte[]>();
        Thread.ofVirtual().start(() -> {
            arrays.add(ResponseWriter.write(SHAPE, names("a"), List.of(), null, (bytes, length, errors) -> bytes));
            arrays.add(ResponseWriter.write(SHAPE, names("b"), List.of(), null, (bytes, length, errors) -> bytes));
        }).join();

        assertThat(arrays.get(1)).isNotSameAs(arrays.get(0));
    }

    @Test
    void errorsOnlyResponseGoesToTheSink() {
        String json = ResponseWriter.writeErrors(List.of(), ResponseWriterSinkTest::string);

        assertThat(json).isEqualTo("{\"errors\":[]}");
    }

    private static String string(byte[] bytes, int length, int errorCount) {
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }

    private static Map<String, Object> names(String... names) {
        return names(List.of(names));
    }

    private static Map<String, Object> names(List<String> names) {
        CompactMap map = new CompactMap();
        map.put("names", names);
        return map;
    }
}
