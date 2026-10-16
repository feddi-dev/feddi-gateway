package dev.feddi.federation.engine.executor;

import graphql.language.AstPrinter;
import graphql.language.OperationDefinition;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Printed forms of subgraph operations, cached by operation instance. Plans are cached and
 * immutable, so the same {@link OperationDefinition} instances are sent again and again; printing
 * them once instead of on every request saves CPU on the hot path.
 *
 * <p>The cache is bounded: when it exceeds {@link #MAX_ENTRIES} it is cleared (operations of
 * replaced plans would otherwise stay referenced forever).
 */
public final class OperationTexts {

    static final int MAX_ENTRIES = 10_000;

    private record Key(OperationDefinition operation) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && key.operation == operation;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(operation);
        }
    }

    private static final Map<Key, String> COMPACT = new ConcurrentHashMap<>();
    private static final Map<Key, String> PRETTY = new ConcurrentHashMap<>();
    private static final Map<Key, byte[]> REQUEST_PREFIX = new ConcurrentHashMap<>();
    private static final byte[] VARIABLES_KEY = ",\"variables\":".getBytes(StandardCharsets.US_ASCII);

    private OperationTexts() {
    }

    /**
     * The operation printed compactly ({@link AstPrinter#printAstCompact}).
     */
    public static String compact(OperationDefinition operation) {
        return lookup(COMPACT, operation, AstPrinter::printAstCompact);
    }

    /**
     * The operation printed as a document ({@link AstPrinter#printAst}), as sent to subgraphs.
     */
    public static String pretty(OperationDefinition operation) {
        return lookup(PRETTY, operation, AstPrinter::printAst);
    }

    /**
     * A GraphQL-over-HTTP request body for the operation ({@link #pretty} text) and its variables: a list of variable
     * sets for a variable batch. Written straight to JSON bytes, with the query part cached.
     */
    public static byte[] requestBody(OperationDefinition operation, Object variables) {
        byte[] prefix = lookup(REQUEST_PREFIX, operation, op -> {
            JsonBuffer buffer = new JsonBuffer(256);
            buffer.write((byte) '{');
            buffer.writeString("query");
            buffer.write((byte) ':');
            buffer.writeString(pretty(op));
            return buffer.toByteArray();
        });
        JsonBuffer body = new JsonBuffer(prefix.length + 256);
        body.write(prefix);
        body.write(VARIABLES_KEY);
        body.writeValue(variables);
        body.write((byte) '}');
        return body.toByteArray();
    }

    /** Number of cached texts (for tests). */
    static int size() {
        return COMPACT.size() + PRETTY.size();
    }

    private static <T> T lookup(Map<Key, T> cache, OperationDefinition operation,
                                Function<OperationDefinition, T> printer) {
        Key key = new Key(operation);
        T text = cache.get(key);
        if (text == null) {
            text = printer.apply(operation);
            if (cache.size() >= MAX_ENTRIES) {
                cache.clear();
            }
            cache.put(key, text);
        }
        return text;
    }
}
