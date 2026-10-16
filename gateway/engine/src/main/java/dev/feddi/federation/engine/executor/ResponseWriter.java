package dev.feddi.federation.engine.executor;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.GraphQLCompositeType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLType;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Writes a GraphQL response as UTF-8 JSON in one walk over the data the executor assembled, guided by the
 * operation's {@link ResponseShape}:
 * <ul>
 *   <li>fields in the order of the selection set ({@link ResponseFieldOrder});</li>
 *   <li>only selected fields, so fields the planner added for its own use are left out;</li>
 *   <li>a null in a non-null position propagates to the nearest nullable parent, with an error unless one exists
 *       at or below its path ({@link NonNullPropagation}): the parent's JSON written so far is discarded and
 *       replaced by null.</li>
 * </ul>
 * The result is the same as completing the data with those classes and serializing it, without building the
 * intermediate maps.
 */
public final class ResponseWriter {

    private static final byte[] ERRORS_KEY = "\"errors\":".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DATA_KEY = "\"data\":".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] EXTENSIONS_KEY = "\"extensions\":".getBytes(StandardCharsets.US_ASCII);

    /**
     * The response as JSON (the first {@code length} bytes of {@code bytes}, which may be longer) and the number of
     * its errors.
     */
    public record Output(byte[] bytes, int length, int errorCount) {

        /** The response as an array of its own length. */
        public byte[] json() {
            return length == bytes.length ? bytes : Arrays.copyOf(bytes, length);
        }
    }

    private final List<GraphQLError> errors;
    private final List<GraphQLError> added = new ArrayList<>(0);
    private final JsonBuffer data;
    private Object[] path = new Object[16];
    private int depth;

    private ResponseWriter(List<GraphQLError> errors, JsonBuffer data) {
        this.errors = errors;
        this.data = data;
    }

    /**
     * Writes a response.
     *
     * @param shape the shape of the operation's response data
     * @param data the data as the executor assembled it, or null
     * @param errors the errors of the execution
     * @param extensions the response extensions, or null
     */
    public static Output write(ResponseShape shape, @Nullable Map<String, Object> data, List<GraphQLError> errors,
                               @Nullable Map<Object, Object> extensions) {
        // Written as {"data":...} into one buffer; errors, if any, go first, which needs a second buffer
        JsonBuffer buffer = new JsonBuffer(8192);
        buffer.write((byte) '{');
        buffer.write(DATA_KEY);
        int dataStart = buffer.position();
        ResponseWriter writer = new ResponseWriter(errors, buffer);
        if (data == null || !writer.writeObject(shape, data)) {
            buffer.reset(dataStart);
            buffer.writeNull();
        }
        List<GraphQLError> allErrors = errors;
        if (!writer.added.isEmpty()) {
            allErrors = new ArrayList<>(errors);
            allErrors.addAll(writer.added);
        }
        JsonBuffer out = buffer;
        if (!allErrors.isEmpty()) {
            out = new JsonBuffer(buffer.position() + 256);
            out.write((byte) '{');
            out.write(ERRORS_KEY);
            writeErrors(out, allErrors);
            out.write((byte) ',');
            out.write(DATA_KEY);
            out.write(buffer, dataStart);
        }
        if (extensions != null) {
            out.write((byte) ',');
            out.write(EXTENSIONS_KEY);
            out.writeValue(extensions);
        }
        out.write((byte) '}');
        return new Output(out.array(), out.position(), allErrors.size());
    }

    /**
     * Writes a response without data, e.g. for a request that failed before execution.
     */
    public static Output writeErrors(List<GraphQLError> errors) {
        JsonBuffer out = new JsonBuffer(256);
        out.write((byte) '{');
        out.write(ERRORS_KEY);
        writeErrors(out, errors);
        out.write((byte) '}');
        return new Output(out.array(), out.position(), errors.size());
    }

    private static void writeErrors(JsonBuffer out, List<GraphQLError> errors) {
        out.write((byte) '[');
        for (int i = 0; i < errors.size(); i++) {
            if (i > 0) {
                out.write((byte) ',');
            }
            out.writeValue(errors.get(i).toSpecification());
        }
        out.write((byte) ']');
    }

    /** Writes an object; false if one of its non-null fields is null, the caller then discards what was written. */
    private boolean writeObject(ResponseShape shape, Map<?, ?> object) {
        data.write((byte) '{');
        boolean first = true;
        String[] keys = shape.keys;
        for (int i = 0; i < keys.length; i++) {
            Object value = object.get(keys[i]);
            if (value == null && !object.containsKey(keys[i])) {
                continue;
            }
            if (!first) {
                data.write((byte) ',');
            }
            first = false;
            data.write(shape.keyBytes[i]);
            push(keys[i]);
            GraphQLType type = shape.types[i];
            boolean valid = type == null ? writeAsIs(value) : writeValue(type, shape.children[i], value, shape.coordinates[i]);
            depth--;
            if (!valid) {
                return false;
            }
        }
        data.write((byte) '}');
        return true;
    }

    /** Writes a value of a field without definition (an introspection field) as it is. */
    private boolean writeAsIs(@Nullable Object value) {
        data.writeValue(value);
        return true;
    }

    /**
     * Writes a value in a position of a type; false if it is null (or invalid) in a non-null position. An invalid
     * value in a nullable position is written as null.
     */
    private boolean writeValue(GraphQLType type, @Nullable ResponseShape shape, @Nullable Object value,
                               String coordinate) {
        if (type instanceof GraphQLNonNull nonNull) {
            if (value == null || !writeInner(nonNull.getWrappedType(), shape, value, coordinate)) {
                reportNull(coordinate);
                return false;
            }
            return true;
        }
        if (value == null) {
            data.writeNull();
            return true;
        }
        int start = data.position();
        if (!writeInner(type, shape, value, coordinate)) {
            data.reset(start);
            data.writeNull();
        }
        return true;
    }

    /** Writes a non-null value of a nullable type: a list's items, an object's fields, or a leaf as it is. */
    private boolean writeInner(GraphQLType type, @Nullable ResponseShape shape, Object value, String coordinate) {
        if (type instanceof GraphQLList list && value instanceof List<?> items) {
            GraphQLType itemType = list.getWrappedType();
            data.write((byte) '[');
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) {
                    data.write((byte) ',');
                }
                push(i);
                boolean valid = writeValue(itemType, shape, items.get(i), coordinate);
                depth--;
                if (!valid) {
                    return false;
                }
            }
            data.write((byte) ']');
            return true;
        }
        if (type instanceof GraphQLCompositeType && shape != null && value instanceof Map<?, ?> object) {
            return writeObject(shape, object);
        }
        data.writeValue(value);
        return true;
    }

    private void push(Object segment) {
        if (depth == path.length) {
            path = Arrays.copyOf(path, depth * 2);
        }
        path[depth++] = segment;
    }

    private void reportNull(String coordinate) {
        List<Object> current = List.of(Arrays.copyOf(path, depth));
        if (hasErrorAtOrBelow(errors, current) || hasErrorAtOrBelow(added, current)) {
            return;
        }
        added.add(GraphqlErrorBuilder.newError()
            .message("Cannot return null for non-nullable field " + coordinate + ".")
            .path(current)
            .build());
    }

    private static boolean hasErrorAtOrBelow(List<GraphQLError> errors, List<Object> path) {
        for (GraphQLError error : errors) {
            List<Object> errorPath = error.getPath();
            if (errorPath != null && errorPath.size() >= path.size() && errorPath.subList(0, path.size()).equals(path)) {
                return true;
            }
        }
        return false;
    }
}
