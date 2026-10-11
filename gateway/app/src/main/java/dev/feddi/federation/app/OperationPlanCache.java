package dev.feddi.federation.app;

import dev.feddi.federation.engine.planner.ExecutionPlan;
import graphql.language.Document;
import graphql.language.OperationDefinition;
import org.jspecify.annotations.Nullable;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Caches prepared operations (normalized document + execution plan) so repeated and
 * persisted queries skip parsing, validation, normalization and planning.
 *
 * <p>Two kinds of keys:
 * <ul>
 *   <li><b>Query text + operation name</b> for regular requests. The validated document is
 *       cached by them, so a hit skips parse and validate as well; only successfully validated
 *       documents are cached, and the result for the same text and schema is always the same.</li>
 *   <li><b>{@link Document} instance</b> for documents returned by a
 *       {@link dev.feddi.federation.extension.DocumentProvider} (persisted documents).
 *       Providers usually return the same cached instance, so identity is the fastest key.</li>
 * </ul>
 *
 * <p>A plan also depends on the values of the variables that {@code @skip}/{@code @include} use:
 * they are evaluated before planning (see
 * {@link dev.feddi.federation.engine.query.OperationNormalizer#conditionValues}), so these values
 * are part of a plan's key. Most operations have none and get one plan.
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

    private record DocumentKey(String query, @Nullable String operationName) {
    }

    private record TextKey(String query, @Nullable String operationName, Map<String, Boolean> conditions) {
    }

    private record IdentityKey(Document document, Map<String, Boolean> conditions) {
        @Override
        public boolean equals(Object other) {
            return other instanceof IdentityKey key && key.document == document && key.conditions.equals(conditions);
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(document) + conditions.hashCode();
        }
    }

    static final int DEFAULT_MAX_ENTRIES = 1_000;

    private final int maxEntries;
    // Prepared operations by TextKey or IdentityKey, validated documents by DocumentKey
    private final ConcurrentHashMap<Object, Object> entries = new ConcurrentHashMap<>();

    OperationPlanCache() {
        this(DEFAULT_MAX_ENTRIES);
    }

    OperationPlanCache(int maxEntries) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be at least 1");
        }
        this.maxEntries = maxEntries;
    }

    /** The validated document of a query text, or null. */
    @Nullable Document getDocument(String query, @Nullable String operationName) {
        return entries.get(new DocumentKey(query, operationName)) instanceof Document document ? document : null;
    }

    void putDocument(String query, @Nullable String operationName, Document document) {
        put(new DocumentKey(query, operationName), document);
    }

    @Nullable PreparedOperation getByText(String query, @Nullable String operationName,
                                          Map<String, Boolean> conditions) {
        return entries.get(new TextKey(query, operationName, conditions)) instanceof PreparedOperation prepared
            ? prepared : null;
    }

    void putByText(String query, @Nullable String operationName, Map<String, Boolean> conditions,
                   PreparedOperation prepared) {
        put(new TextKey(query, operationName, Map.copyOf(conditions)), prepared);
    }

    /**
     * Returns the prepared operation for a provider-supplied document and condition values,
     * preparing and caching it on a miss. Preparation failures are not cached.
     */
    PreparedOperation getOrPrepare(Document document, Map<String, Boolean> conditions,
                                   Supplier<PreparedOperation> prepare) {
        IdentityKey key = new IdentityKey(document, Map.copyOf(conditions));
        if (entries.get(key) instanceof PreparedOperation cached) {
            return cached;
        }
        PreparedOperation prepared = prepare.get();
        put(key, prepared);
        return prepared;
    }

    int size() {
        return entries.size();
    }

    private void put(Object key, Object value) {
        entries.put(key, value);
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
