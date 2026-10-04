package dev.feddi.federation.engine.executor;

import graphql.language.AstPrinter;
import graphql.language.OperationDefinition;

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

    private static String lookup(Map<Key, String> cache, OperationDefinition operation,
                                 Function<OperationDefinition, String> printer) {
        Key key = new Key(operation);
        String text = cache.get(key);
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
