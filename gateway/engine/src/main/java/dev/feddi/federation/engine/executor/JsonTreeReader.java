package dev.feddi.federation.engine.executor;

import dev.feddi.federation.engine.IntrospectionFields;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.math.BigInteger;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reads JSON into the executor's data structures: objects as {@link CompactMap}s with interned field names, arrays
 * as {@code ArrayList}s, strings as {@link RawString}s (except {@code __typename} values, which the executor
 * compares, as {@code String}s), integers as {@code Integer}, {@code Long} or {@code BigInteger}, decimals as
 * {@code Double}. Reads one value or a sequence of root-level values (JSON lines).
 */
public final class JsonTreeReader {

    private static final int SYMBOLS = 4096;
    /** Interned field names by the hash of their bytes (racy, but entries are immutable). */
    private static final Symbol[] SYMBOL_TABLE = new Symbol[SYMBOLS];

    private record Symbol(byte[] bytes, String name) {
    }

    private final byte[] in;
    private int pos;
    private Object[] stack = new Object[64];
    private int stackSize;

    private JsonTreeReader(byte[] in) {
        this.in = in;
    }

    /**
     * Reads one JSON value.
     *
     * @throws IOException if the bytes are not one JSON value
     */
    public static @Nullable Object read(byte[] json) throws IOException {
        JsonTreeReader reader = new JsonTreeReader(json);
        try {
            reader.skipWhitespace();
            if (reader.pos >= json.length) {
                throw new IOException("No JSON value");
            }
            Object value = reader.readValue(null);
            reader.skipWhitespace();
            if (reader.pos != json.length) {
                throw new IOException("More than one JSON value at " + reader.pos);
            }
            return value;
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new IOException("Unexpected end of JSON", e);
        }
    }

    /**
     * Reads a sequence of root-level JSON values, separated by whitespace (JSON lines).
     *
     * @throws IOException if the bytes are not JSON values
     */
    public static List<@Nullable Object> readSequence(byte[] json) throws IOException {
        JsonTreeReader reader = new JsonTreeReader(json);
        List<Object> values = new ArrayList<>();
        try {
            reader.skipWhitespace();
            while (reader.pos < json.length) {
                values.add(reader.readValue(null));
                reader.skipWhitespace();
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new IOException("Unexpected end of JSON", e);
        }
        return values;
    }

    private @Nullable Object readValue(@Nullable String fieldName) throws IOException {
        byte b = in[pos];
        switch (b) {
            case '{' -> {
                return readObject();
            }
            case '[' -> {
                return readArray();
            }
            case '"' -> {
                int start = pos;
                skipString();
                // Interned names compare by reference
                return fieldName == IntrospectionFields.TYPENAME ? decodeString(in, start, pos)
                    : new RawString(in, start, pos);
            }
            case 't' -> {
                expectLiteral("true");
                return Boolean.TRUE;
            }
            case 'f' -> {
                expectLiteral("false");
                return Boolean.FALSE;
            }
            case 'n' -> {
                expectLiteral("null");
                return null;
            }
            default -> {
                if (b == '-' || b >= '0' && b <= '9') {
                    return readNumber();
                }
                throw new IOException("Unexpected character '" + (char) b + "' at " + pos);
            }
        }
    }

    private CompactMap readObject() throws IOException {
        pos++;
        CompactMap object = new CompactMap();
        skipWhitespace();
        if (in[pos] == '}') {
            pos++;
            return object;
        }
        while (true) {
            if (in[pos] != '"') {
                throw new IOException("Expected a field name at " + pos);
            }
            String name = readName();
            skipWhitespace();
            if (in[pos++] != ':') {
                throw new IOException("Expected ':' at " + (pos - 1));
            }
            skipWhitespace();
            // Field names of one object are distinct in practice (duplicates, which JSON leaves undefined, keep
            // both entries and lookups find the first)
            object.append(name, readValue(name));
            skipWhitespace();
            byte next = in[pos++];
            if (next == '}') {
                return object;
            }
            if (next != ',') {
                throw new IOException("Expected ',' or '}' at " + (pos - 1));
            }
            skipWhitespace();
        }
    }

    /**
     * Reads an array. Its items are collected on a stack shared by all arrays of the document and copied into a list
     * of the right size, instead of growing a list.
     */
    private List<Object> readArray() throws IOException {
        pos++;
        skipWhitespace();
        if (in[pos] == ']') {
            pos++;
            return new ArrayList<>(0);
        }
        int base = stackSize;
        while (true) {
            Object item = readValue(null);
            if (stackSize == stack.length) {
                stack = Arrays.copyOf(stack, stack.length * 2);
            }
            stack[stackSize++] = item;
            skipWhitespace();
            byte next = in[pos++];
            if (next == ']') {
                List<Object> array = new ArrayList<>(stackSize - base);
                for (int i = base; i < stackSize; i++) {
                    array.add(stack[i]);
                    stack[i] = null;
                }
                stackSize = base;
                return array;
            }
            if (next != ',') {
                throw new IOException("Expected ',' or ']' at " + (pos - 1));
            }
            skipWhitespace();
        }
    }

    /** Moves past a string starting at the quote at {@code pos}. */
    private void skipString() throws IOException {
        int at = pos + 1;
        byte[] in = this.in;
        // Eight bytes at a time while none is a quote, a backslash or a control character (SWAR)
        int last = in.length - Long.BYTES;
        while (at <= last) {
            long word = (long) LONGS.get(in, at);
            if (hasZeroByte(word ^ QUOTES) || hasZeroByte(word ^ BACKSLASHES) || hasControl(word)) {
                break;
            }
            at += Long.BYTES;
        }
        while (true) {
            byte b = in[at];
            if (b == '"') {
                pos = at + 1;
                return;
            }
            if (b == '\\') {
                at += 2;
            } else if (b >= 0 && b < 0x20) {
                throw new IOException("Control character in string at " + at);
            } else {
                at++;
            }
        }
    }

    private static final long ONES = 0x0101010101010101L;
    private static final long HIGH_BITS = 0x8080808080808080L;
    private static final long QUOTES = 0x2222222222222222L;
    private static final long BACKSLASHES = 0x5C5C5C5C5C5C5C5CL;
    private static final long SPACES = 0x2020202020202020L;
    private static final VarHandle LONGS = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /** Whether one of the eight bytes is zero. */
    private static boolean hasZeroByte(long word) {
        return ((word - ONES) & ~word & HIGH_BITS) != 0;
    }

    /** Whether one of the eight bytes is an ASCII control character (below 0x20). */
    private static boolean hasControl(long word) {
        return ((word - SPACES) & ~word & HIGH_BITS) != 0;
    }

    /** Reads a field name, interned (through a symbol table, so repeated names cost a byte comparison). */
    private String readName() throws IOException {
        int start = pos;
        int hash = 0;
        int at = pos + 1;
        boolean escaped = false;
        while (true) {
            byte b = in[at];
            if (b == '"') {
                break;
            }
            if (b == '\\') {
                escaped = true;
                at += 2;
                continue;
            }
            hash = 31 * hash + b;
            at++;
        }
        pos = at + 1;
        if (escaped) {
            return decodeString(in, start, pos).intern();
        }
        int slot = (hash ^ (hash >>> 16)) & (SYMBOLS - 1);
        Symbol symbol = SYMBOL_TABLE[slot];
        if (symbol != null && Arrays.equals(symbol.bytes(), 0, symbol.bytes().length, in, start + 1, at)) {
            return symbol.name();
        }
        String name = new String(in, start + 1, at - start - 1, StandardCharsets.UTF_8).intern();
        SYMBOL_TABLE[slot] = new Symbol(Arrays.copyOfRange(in, start + 1, at), name);
        return name;
    }

    private Object readNumber() throws IOException {
        int start = pos;
        boolean negative = in[pos] == '-';
        if (negative) {
            pos++;
        }
        long value = 0;
        int digits = 0;
        while (pos < in.length && in[pos] >= '0' && in[pos] <= '9') {
            value = value * 10 + (in[pos] - '0');
            digits++;
            pos++;
        }
        if (digits == 0) {
            throw new IOException("Invalid number at " + start);
        }
        if (pos < in.length && (in[pos] == '.' || in[pos] == 'e' || in[pos] == 'E')) {
            pos++;
            while (pos < in.length && (in[pos] >= '0' && in[pos] <= '9' || in[pos] == 'e' || in[pos] == 'E'
                || in[pos] == '+' || in[pos] == '-')) {
                pos++;
            }
            try {
                return Double.parseDouble(new String(in, start, pos - start, StandardCharsets.ISO_8859_1));
            } catch (NumberFormatException e) {
                throw new IOException("Invalid number at " + start, e);
            }
        }
        if (digits > 18) {
            BigInteger big = new BigInteger(new String(in, start, pos - start, StandardCharsets.ISO_8859_1));
            return big.bitLength() < 64 ? (Object) big.longValue() : big;
        }
        long signed = negative ? -value : value;
        if (signed >= Integer.MIN_VALUE && signed <= Integer.MAX_VALUE) {
            return (int) signed;
        }
        return signed;
    }

    private void expectLiteral(String literal) throws IOException {
        for (int i = 0; i < literal.length(); i++) {
            if (in[pos + i] != literal.charAt(i)) {
                throw new IOException("Invalid literal at " + pos);
            }
        }
        pos += literal.length();
    }

    private void skipWhitespace() {
        byte[] in = this.in;
        int at = pos;
        while (at < in.length) {
            byte b = in[at];
            if (b != ' ' && b != '\n' && b != '\r' && b != '\t') {
                break;
            }
            at++;
        }
        pos = at;
    }

    /**
     * Decodes a JSON string from its text.
     *
     * @param start the index of the opening quote
     * @param end the index after the closing quote
     */
    static String decodeString(byte[] bytes, int start, int end) {
        String content = new String(bytes, start + 1, end - start - 2, StandardCharsets.UTF_8);
        int backslash = content.indexOf('\\');
        if (backslash < 0) {
            return content;
        }
        StringBuilder decoded = new StringBuilder(content.length());
        decoded.append(content, 0, backslash);
        for (int i = backslash; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c != '\\' || i + 1 >= content.length()) {
                decoded.append(c);
                continue;
            }
            char escape = content.charAt(++i);
            switch (escape) {
                case 'b' -> decoded.append('\b');
                case 'f' -> decoded.append('\f');
                case 'n' -> decoded.append('\n');
                case 'r' -> decoded.append('\r');
                case 't' -> decoded.append('\t');
                case 'u' -> {
                    if (i + 4 < content.length()) {
                        decoded.append((char) Integer.parseInt(content, i + 1, i + 5, 16));
                        i += 4;
                    }
                }
                default -> decoded.append(escape);
            }
        }
        return decoded.toString();
    }
}
