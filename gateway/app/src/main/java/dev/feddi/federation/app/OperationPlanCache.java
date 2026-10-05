package dev.feddi.federation.app;

import dev.feddi.federation.engine.planner.ExecutionPlan;
import graphql.language.Document;
import graphql.language.OperationDefinition;
import org.jspecify.annotations.Nullable;

import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Caches prepared operations (normalized document + execution plan) so repeated and
 * persisted queries skip parsing, validation, normalization and planning.
 *
 * <p>Two kinds of keys:
 * <ul>
 *   <li><b>Query text + operation name</b> for regular requests. A hit skips parse and
 *       validate as well; only successfully validated documents are cached, and the result
 *       for the same text and schema is always the same.</li>
 *   <li><b>{@link Document} instance</b> for documents returned by a
 *       {@link dev.feddi.federation.extension.DocumentProvider} (persisted documents).
 *       Providers usually return the same cached instance, so identity is the fastest key.</li>
 * </ul>
 *
 * <p>Plans do not depend on variable values (variable-based {@code @skip}/{@code @include}
 * is evaluated by the subgraphs), so variables are not part of the key.
 *
 * <p>One cache belongs to one gateway instance; a schema reload creates a new gateway and
 * thereby a new, empty cache. The size bound is approximate: when it is exceeded, a batch
 * of entries is evicted in arbitrary order. That keeps reads lock-free.
 */
final class OperationPlanCache {

    /**
     * A prepared operation: the normalized document (passed on to usage reporting), the operation
     * in it (its selection set orders the response fields) and its plan.
     */
    record PreparedOperation(Document document, OperationDefinition operation, ExecutionPlan plan) {
        PreparedOperation {
            Objects.requireNonNull(document, "document");
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(plan, "plan");
        }
    }

    private record TextKey(String query, @Nullable String operationName) {
    }

    private record IdentityKey(Document document) {
        @Override
        public boolean equals(Object other) {
            return other instanceof IdentityKey key && key.document == document;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(document);
        }
    }

    static final int DEFAULT_MAX_ENTRIES = 1_000;

    private final int maxEntries;
    private final ConcurrentHashMap<Object, PreparedOperation> entries = new ConcurrentHashMap<>();

    OperationPlanCache() {
        this(DEFAULT_MAX_ENTRIES);
    }

    OperationPlanCache(int maxEntries) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be at least 1");
        }
        this.maxEntries = maxEntries;
    }

    @Nullable PreparedOperation getByText(String query, @Nullable String operationName) {
        return entries.get(new TextKey(query, operationName));
    }

    void putByText(String query, @Nullable String operationName, PreparedOperation prepared) {
        put(new TextKey(query, operationName), prepared);
    }

    /**
     * Returns the prepared operation for a provider-supplied document, preparing and caching
     * it on a miss. Preparation failures are not cached.
     */
    PreparedOperation getOrPrepare(Document document, Supplier<PreparedOperation> prepare) {
        IdentityKey key = new IdentityKey(document);
        PreparedOperation cached = entries.get(key);
        if (cached != null) {
            return cached;
        }
        PreparedOperation prepared = prepare.get();
        put(key, prepared);
        return prepared;
    }

    int size() {
        return entries.size();
    }

    private void put(Object key, PreparedOperation prepared) {
        entries.put(key, prepared);
        if (entries.size() > maxEntries) {
            evict();
        }
    }

    private void evict() {
        int toRemove = Math.max(1, maxEntries / 10);
        Iterator<Object> keys = entries.keySet().iterator();
        while (toRemove-- > 0 && keys.hasNext()) {
            keys.next();
            keys.remove();
        }
    }
}
