package dev.feddi.federation.engine.executor;

import org.jspecify.annotations.Nullable;

import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * An insertion-ordered map of string keys for response objects: keys and values in two arrays, looked up by a scan
 * that compares references and hash codes before {@code equals}. GraphQL objects have few fields and their keys come
 * from interned field names, so this is faster and much smaller than a {@code LinkedHashMap}. Null keys are not
 * supported; null values are.
 */
public final class CompactMap extends AbstractMap<String, @Nullable Object> {

    private String[] keys;
    private @Nullable Object[] values;
    private int size;

    public CompactMap() {
        this(8);
    }

    public CompactMap(int capacity) {
        keys = new String[Math.max(1, capacity)];
        values = new Object[keys.length];
    }

    /** A copy of a map, in its iteration order. */
    public CompactMap(Map<String, ?> source) {
        this(source.size());
        source.forEach(this::put);
    }

    private int indexOf(@Nullable Object key) {
        if (!(key instanceof String string)) {
            return -1;
        }
        String[] keys = this.keys;
        int hash = string.hashCode();
        for (int i = 0; i < size; i++) {
            String candidate = keys[i];
            if (candidate == string || candidate.hashCode() == hash && candidate.equals(string)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The index of a key, trying the position {@code hint} first: objects usually hold their fields in the order in
     * which a writer asks for them. -1 if the map has no such key.
     */
    int indexOf(String key, int hint) {
        if (hint < size && keys[hint] == key) {
            return hint;
        }
        return indexOf(key);
    }

    /** The value at an index found with {@link #indexOf(String, int)}. */
    @Nullable Object valueAt(int index) {
        return values[index];
    }

    /** Replaces the value at an index found with {@link #indexOf(String, int)}. */
    void setValueAt(int index, @Nullable Object value) {
        values[index] = value;
    }

    /**
     * Adds an entry without looking for an existing one with the key: for building a map whose keys are known to be
     * distinct, such as a parsed JSON object.
     */
    void append(String key, @Nullable Object value) {
        if (size == keys.length) {
            keys = Arrays.copyOf(keys, size * 2);
            values = Arrays.copyOf(values, size * 2);
        }
        keys[size] = key;
        values[size] = value;
        size++;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean containsKey(@Nullable Object key) {
        return indexOf(key) >= 0;
    }

    @Override
    public @Nullable Object get(@Nullable Object key) {
        int index = indexOf(key);
        return index < 0 ? null : values[index];
    }

    @Override
    public @Nullable Object put(String key, @Nullable Object value) {
        int index = indexOf(key);
        if (index >= 0) {
            Object previous = values[index];
            values[index] = value;
            return previous;
        }
        Objects.requireNonNull(key, "map keys cannot be null");
        append(key, value);
        return null;
    }

    @Override
    public @Nullable Object remove(@Nullable Object key) {
        int index = indexOf(key);
        if (index < 0) {
            return null;
        }
        Object previous = values[index];
        removeAt(index);
        return previous;
    }

    private void removeAt(int index) {
        int moved = size - index - 1;
        System.arraycopy(keys, index + 1, keys, index, moved);
        System.arraycopy(values, index + 1, values, index, moved);
        size--;
        keys[size] = null;
        values[size] = null;
    }

    @Override
    public void clear() {
        Arrays.fill(keys, 0, size, null);
        Arrays.fill(values, 0, size, null);
        size = 0;
    }

    @Override
    public void forEach(BiConsumer<? super String, ? super @Nullable Object> action) {
        for (int i = 0; i < size; i++) {
            action.accept(keys[i], values[i]);
        }
    }

    @Override
    public int hashCode() {
        int hash = 0;
        for (int i = 0; i < size; i++) {
            hash += keys[i].hashCode() ^ Objects.hashCode(values[i]);
        }
        return hash;
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if (!(other instanceof Map<?, ?> map) || map.size() != size) {
            return false;
        }
        for (int i = 0; i < size; i++) {
            Object value = values[i];
            Object otherValue = map.get(keys[i]);
            if (!Objects.equals(value, otherValue) || otherValue == null && !map.containsKey(keys[i])) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Collection<@Nullable Object> values() {
        return new AbstractCollection<>() {
            @Override
            public Iterator<@Nullable Object> iterator() {
                return new Iterator<>() {
                    private int next;

                    @Override
                    public boolean hasNext() {
                        return next < size;
                    }

                    @Override
                    public @Nullable Object next() {
                        if (next >= size) {
                            throw new NoSuchElementException();
                        }
                        return values[next++];
                    }
                };
            }

            @Override
            public int size() {
                return size;
            }
        };
    }

    @Override
    public Set<Entry<String, @Nullable Object>> entrySet() {
        return new AbstractSet<>() {
            @Override
            public Iterator<Entry<String, @Nullable Object>> iterator() {
                return new EntryIterator();
            }

            @Override
            public int size() {
                return size;
            }
        };
    }

    private final class EntryIterator implements Iterator<Entry<String, @Nullable Object>> {
        private int next;
        private int last = -1;

        @Override
        public boolean hasNext() {
            return next < size;
        }

        @Override
        public Entry<String, @Nullable Object> next() {
            if (next >= size) {
                throw new NoSuchElementException();
            }
            last = next++;
            return new MapEntry(last);
        }

        @Override
        public void remove() {
            if (last < 0) {
                throw new IllegalStateException();
            }
            removeAt(last);
            next = last;
            last = -1;
        }
    }

    private final class MapEntry implements Entry<String, @Nullable Object> {
        private final int index;

        MapEntry(int index) {
            this.index = index;
        }

        @Override
        public String getKey() {
            return keys[index];
        }

        @Override
        public @Nullable Object getValue() {
            return values[index];
        }

        @Override
        public @Nullable Object setValue(@Nullable Object value) {
            Object previous = values[index];
            values[index] = value;
            return previous;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Entry<?, ?> entry && getKey().equals(entry.getKey())
                && Objects.equals(getValue(), entry.getValue());
        }

        @Override
        public int hashCode() {
            return getKey().hashCode() ^ Objects.hashCode(getValue());
        }

        @Override
        public String toString() {
            return getKey() + "=" + getValue();
        }
    }
}
