package dev.feddi.federation.engine.executor;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads JSON into the executor's data structures: objects as {@link CompactMap}s (field names interned, as Jackson
 * does by default), arrays as {@code ArrayList}s, integers as {@code Integer}, {@code Long} or {@code BigInteger},
 * decimals as {@code Double}. Reads one value or a sequence of root-level values (JSON lines).
 */
public final class JsonTreeReader {

    private static final JsonFactory FACTORY = new JsonFactory();

    private JsonTreeReader() {
    }

    /**
     * Reads one JSON value.
     *
     * @throws IOException if the bytes are not one JSON value
     */
    public static @Nullable Object read(byte[] json) throws IOException {
        try (JsonParser parser = FACTORY.createParser(json)) {
            JsonToken token = parser.nextToken();
            if (token == null) {
                throw new IOException("No JSON value");
            }
            Object value = readValue(parser, token);
            if (parser.nextToken() != null) {
                throw new IOException("More than one JSON value");
            }
            return value;
        }
    }

    /**
     * Reads a sequence of root-level JSON values, separated by whitespace (JSON lines).
     *
     * @throws IOException if the bytes are not JSON values
     */
    public static List<@Nullable Object> readSequence(byte[] json) throws IOException {
        List<Object> values = new ArrayList<>();
        try (JsonParser parser = FACTORY.createParser(json)) {
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                values.add(readValue(parser, token));
            }
        }
        return values;
    }

    private static @Nullable Object readValue(JsonParser parser, JsonToken token) throws IOException {
        switch (token) {
            case START_OBJECT -> {
                CompactMap object = new CompactMap();
                String name;
                while ((name = parser.nextFieldName()) != null) {
                    // Field names of one object are distinct in practice (duplicates, which JSON leaves
                    // undefined, keep both entries and lookups find the first)
                    object.append(name, readValue(parser, parser.nextToken()));
                }
                return object;
            }
            case START_ARRAY -> {
                List<Object> array = new ArrayList<>();
                JsonToken next;
                while ((next = parser.nextToken()) != JsonToken.END_ARRAY) {
                    array.add(readValue(parser, next));
                }
                return array;
            }
            case VALUE_STRING -> {
                return parser.getText();
            }
            case VALUE_NUMBER_INT -> {
                return parser.getNumberValue();
            }
            case VALUE_NUMBER_FLOAT -> {
                return parser.getDoubleValue();
            }
            case VALUE_TRUE -> {
                return Boolean.TRUE;
            }
            case VALUE_FALSE -> {
                return Boolean.FALSE;
            }
            case VALUE_NULL -> {
                return null;
            }
            default -> throw new IOException("Unexpected JSON token " + token);
        }
    }
}
