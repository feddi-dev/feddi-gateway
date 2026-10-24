package dev.feddi.federation.engine.executor;

import org.jspecify.annotations.Nullable;

import java.util.Arrays;

/**
 * A string value of a subgraph response, kept as its JSON text (UTF-8, with quotes and escapes) and decoded only
 * when needed. Most strings of a response are passed through to the gateway's response unchanged, which then copies
 * the bytes instead of decoding and encoding them.
 *
 * <p>Equal to another {@code RawString} with the same JSON text or the same decoded value; {@link #hashCode()} is the
 * decoded {@code String}'s. A {@code String} is never equal to a {@code RawString}: code that compares values with
 * strings ({@code __typename}, error paths) gets them decoded from the {@link JsonTreeReader}, and completed results
 * are converted with {@link #decodeAll(Object)}.
 */
public final class RawString implements CharSequence {

    private final byte[] bytes;
    private final int start;
    private final int end;
    private @Nullable String decoded;
    /** The hash code once computed (0 before): values are hashed again and again as parts of lookup keys. */
    private int hash;

    /**
     * @param bytes the JSON text
     * @param start the index of the opening quote
     * @param end the index after the closing quote
     */
    RawString(byte[] bytes, int start, int end) {
        this.bytes = bytes;
        this.start = start;
        this.end = end;
    }

    /** Writes the JSON text (with quotes) to a buffer. */
    void writeTo(JsonBuffer buffer) {
        buffer.write(bytes, start, end - start);
    }

    @Override
    public String toString() {
        String value = decoded;
        if (value == null) {
            value = JsonTreeReader.decodeString(bytes, start, end);
            decoded = value;
        }
        return value;
    }

    @Override
    public int length() {
        return toString().length();
    }

    @Override
    public char charAt(int index) {
        return toString().charAt(index);
    }

    @Override
    public CharSequence subSequence(int from, int to) {
        return toString().subSequence(from, to);
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if (!(other instanceof RawString raw)) {
            return false;
        }
        if (Arrays.equals(bytes, start, end, raw.bytes, raw.start, raw.end)) {
            return true;
        }
        return toString().equals(raw.toString());
    }

    /**
     * The decoded {@code String}'s hash code; computed from the bytes for ASCII text without escapes (most keys and
     * IDs), which decodes to the same characters.
     */
    @Override
    public int hashCode() {
        int h = hash;
        if (h == 0) {
            h = computeHash();
            hash = h;
        }
        return h;
    }

    private int computeHash() {
        if (decoded != null) {
            return decoded.hashCode();
        }
        int h = 0;
        for (int i = start + 1; i < end - 1; i++) {
            byte b = bytes[i];
            if (b < 0 || b == '\\') {
                return toString().hashCode();
            }
            h = 31 * h + b;
        }
        return h;
    }

    /**
     * A copy of a value with every {@code RawString} in it (also in maps and lists) decoded to a {@code String};
     * the value itself if it has none.
     */
    public static @Nullable Object decodeAll(@Nullable Object value) {
        if (value instanceof RawString raw) {
            return raw.toString();
        }
        if (value instanceof java.util.Map<?, ?> map) {
            CompactMap copy = null;
            int index = 0;
            for (java.util.Map.Entry<?, ?> entry : map.entrySet()) {
                Object decodedValue = decodeAll(entry.getValue());
                if (copy == null && decodedValue != entry.getValue()) {
                    copy = new CompactMap(map.size());
                    int copied = 0;
                    for (java.util.Map.Entry<?, ?> earlier : map.entrySet()) {
                        if (copied++ == index) {
                            break;
                        }
                        copy.append((String) earlier.getKey(), earlier.getValue());
                    }
                }
                if (copy != null) {
                    copy.append((String) entry.getKey(), decodedValue);
                }
                index++;
            }
            return copy == null ? map : copy;
        }
        if (value instanceof java.util.List<?> list) {
            java.util.List<Object> copy = null;
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                Object decodedItem = decodeAll(item);
                if (copy == null && decodedItem != item) {
                    copy = new java.util.ArrayList<>(list.subList(0, i));
                }
                if (copy != null) {
                    copy.add(decodedItem);
                }
            }
            return copy == null ? list : copy;
        }
        return value;
    }
}
