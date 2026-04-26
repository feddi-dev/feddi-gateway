package dev.feddi.federation.engine.parser;

import java.io.IOException;
import java.io.Reader;
import java.nio.CharBuffer;
import java.util.function.Consumer;

/**
 * A reader that limits the maximum number of characters that can be read.
 * This is used to protect against DOS attacks with very large inputs.
 */
public class SafeTokenReader extends Reader {

    private final Reader delegate;
    private final int maxCharacters;
    private final Consumer<Integer> whenMaxCharactersExceeded;
    private int count;

    public SafeTokenReader(Reader delegate, int maxCharacters, Consumer<Integer> whenMaxCharactersExceeded) {
        this.delegate = delegate;
        this.maxCharacters = maxCharacters;
        this.whenMaxCharactersExceeded = whenMaxCharactersExceeded;
        count = 0;
    }

    private int checkHowMany(int read, int howMany) {
        if (read != -1) {
            count += howMany;
            if (count > maxCharacters) {
                whenMaxCharactersExceeded.accept(maxCharacters);
            }
        }
        return read;
    }

    @Override
    public int read(char[] buff, int off, int len) throws IOException {
        int howMany = delegate.read(buff, off, len);
        return checkHowMany(howMany, howMany);
    }

    @Override
    public int read() throws IOException {
        int ch = delegate.read();
        return checkHowMany(ch, 1);
    }

    @Override
    public int read(CharBuffer target) throws IOException {
        int howMany = delegate.read(target);
        return checkHowMany(howMany, howMany);
    }

    @Override
    public int read(char[] buff) throws IOException {
        int howMany = delegate.read(buff);
        return checkHowMany(howMany, howMany);
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    @Override
    public long skip(long n) throws IOException {
        return delegate.skip(n);
    }

    @Override
    public boolean ready() throws IOException {
        return delegate.ready();
    }

    @Override
    public boolean markSupported() {
        return delegate.markSupported();
    }

    @Override
    public void mark(int readAheadLimit) throws IOException {
        delegate.mark(readAheadLimit);
    }

    @Override
    public void reset() throws IOException {
        delegate.reset();
    }
}
