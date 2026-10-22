package dev.feddi.federation.engine.executor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Values computed from plan objects, cached by instance: plans are cached and immutable, so the same instances are
 * executed again and again. Bounded like {@link OperationTexts}: when it exceeds {@link #MAX_ENTRIES} it is cleared
 * (objects of replaced plans would otherwise stay referenced forever).
 */
final class IdentityCache<K, V> {

    static final int MAX_ENTRIES = 10_000;

    private record Key(Object object) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && key.object == object;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(object);
        }
    }

    private final Map<Key, V> values = new ConcurrentHashMap<>();
    private final Function<K, V> compute;

    IdentityCache(Function<K, V> compute) {
        this.compute = compute;
    }

    V get(K object) {
        Key key = new Key(object);
        V value = values.get(key);
        if (value == null) {
            value = compute.apply(object);
            if (values.size() >= MAX_ENTRIES) {
                values.clear();
            }
            values.put(key, value);
        }
        return value;
    }
}
