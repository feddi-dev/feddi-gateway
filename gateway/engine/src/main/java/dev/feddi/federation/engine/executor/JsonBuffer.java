package dev.feddi.federation.engine.executor;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

/**
 * A growable buffer of UTF-8 JSON. Writing can be undone back to a {@link #position() position}, which is how the
 * {@link ResponseWriter} replaces a value with null when a non-null field inside it turns out to be null.
 */
final class JsonBuffer {

    private static final byte[] NULL = {'n', 'u', 'l', 'l'};
    private static final byte[] TRUE = {'t', 'r', 'u', 'e'};
    private static final byte[] FALSE = {'f', 'a', 'l', 's', 'e'};
    private static final byte[] HEX = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    private byte[] bytes;
    private int size;

    JsonBuffer(int capacity) {
        bytes = new byte[Math.max(16, capacity)];
    }

    int position() {
        return size;
    }

    /** Discards everything written after the position. */
    void reset(int position) {
        size = position;
    }

    byte[] toByteArray() {
        return Arrays.copyOf(bytes, size);
    }

    void write(byte b) {
        if (size == bytes.length) {
            grow(1);
        }
        bytes[size++] = b;
    }

    void write(byte[] raw) {
        if (size + raw.length > bytes.length) {
            grow(raw.length);
        }
        System.arraycopy(raw, 0, bytes, size, raw.length);
        size += raw.length;
    }

    void writeNull() {
        write(NULL);
    }

    /** Writes the bytes of another buffer. */
    void write(JsonBuffer other) {
        if (size + other.size > bytes.length) {
            grow(other.size);
        }
        System.arraycopy(other.bytes, 0, bytes, size, other.size);
        size += other.size;
    }

    /** Writes a JSON string. */
    void writeString(String value) {
        int length = value.length();
        // At most 6 bytes per char (an escaped control character), plus the quotes
        if (size + length * 6 + 2 > bytes.length) {
            grow(length * 6 + 2);
        }
        byte[] out = bytes;
        int at = size;
        out[at++] = '"';
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (c < 0x80) {
                if (c >= 0x20 && c != '"' && c != '\\') {
                    out[at++] = (byte) c;
                } else {
                    at = escape(out, at, c);
                }
            } else if (c < 0x800) {
                out[at++] = (byte) (0xC0 | (c >> 6));
                out[at++] = (byte) (0x80 | (c & 0x3F));
            } else if (Character.isHighSurrogate(c) && i + 1 < length && Character.isLowSurrogate(value.charAt(i + 1))) {
                int codePoint = Character.toCodePoint(c, value.charAt(++i));
                out[at++] = (byte) (0xF0 | (codePoint >> 18));
                out[at++] = (byte) (0x80 | ((codePoint >> 12) & 0x3F));
                out[at++] = (byte) (0x80 | ((codePoint >> 6) & 0x3F));
                out[at++] = (byte) (0x80 | (codePoint & 0x3F));
            } else if (Character.isSurrogate(c)) {
                // A lone surrogate has no UTF-8 encoding: escape it, as JSON allows
                at = escape(out, at, c);
            } else {
                out[at++] = (byte) (0xE0 | (c >> 12));
                out[at++] = (byte) (0x80 | ((c >> 6) & 0x3F));
                out[at++] = (byte) (0x80 | (c & 0x3F));
            }
        }
        out[at++] = '"';
        size = at;
    }

    private static int escape(byte[] out, int at, char c) {
        out[at++] = '\\';
        switch (c) {
            case '"' -> out[at++] = '"';
            case '\\' -> out[at++] = '\\';
            case '\n' -> out[at++] = 'n';
            case '\r' -> out[at++] = 'r';
            case '\t' -> out[at++] = 't';
            case '\b' -> out[at++] = 'b';
            case '\f' -> out[at++] = 'f';
            default -> {
                out[at++] = 'u';
                out[at++] = HEX[(c >> 12) & 0xF];
                out[at++] = HEX[(c >> 8) & 0xF];
                out[at++] = HEX[(c >> 4) & 0xF];
                out[at++] = HEX[c & 0xF];
            }
        }
        return at;
    }

    /**
     * Writes any value as JSON: strings, numbers, booleans, null, maps (keys as strings) and collections; other
     * values as their string.
     */
    void writeValue(Object value) {
        switch (value) {
            case null -> writeNull();
            case String string -> writeString(string);
            case Integer number -> writeAscii(Integer.toString(number));
            case Long number -> writeAscii(Long.toString(number));
            case Boolean bool -> write(bool ? TRUE : FALSE);
            case Double number -> writeFloating(number);
            case Float number -> writeFloating(number.doubleValue());
            case Short number -> writeAscii(Short.toString(number));
            case Byte number -> writeAscii(Byte.toString(number));
            case BigInteger number -> writeAscii(number.toString());
            case BigDecimal number -> writeAscii(number.toString());
            case Map<?, ?> map -> {
                write((byte) '{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        write((byte) ',');
                    }
                    first = false;
                    writeString(String.valueOf(entry.getKey()));
                    write((byte) ':');
                    writeValue(entry.getValue());
                }
                write((byte) '}');
            }
            case Collection<?> collection -> {
                write((byte) '[');
                boolean first = true;
                for (Object item : collection) {
                    if (!first) {
                        write((byte) ',');
                    }
                    first = false;
                    writeValue(item);
                }
                write((byte) ']');
            }
            case Enum<?> constant -> writeString(constant.name());
            default -> writeString(String.valueOf(value));
        }
    }

    private void writeFloating(double number) {
        if (Double.isFinite(number)) {
            writeAscii(Double.toString(number));
        } else {
            writeNull();
        }
    }

    private void writeAscii(String ascii) {
        int length = ascii.length();
        if (size + length > bytes.length) {
            grow(length);
        }
        for (int i = 0; i < length; i++) {
            bytes[size++] = (byte) ascii.charAt(i);
        }
    }

    private void grow(int needed) {
        bytes = Arrays.copyOf(bytes, Math.max(bytes.length * 2, size + needed));
    }
}
