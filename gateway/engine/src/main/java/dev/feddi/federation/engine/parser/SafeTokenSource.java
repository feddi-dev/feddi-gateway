package dev.feddi.federation.engine.parser;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenFactory;
import org.antlr.v4.runtime.TokenSource;

import java.util.function.BiConsumer;

/**
 * A token source that limits the maximum number of tokens that can be produced.
 * This is used to protect against DOS attacks with inputs that generate many tokens.
 */
public class SafeTokenSource implements TokenSource {

    private final TokenSource lexer;
    private final int maxTokens;
    private final BiConsumer<Integer, Token> whenMaxTokensExceeded;
    private final int[] channelCounts;

    public SafeTokenSource(TokenSource lexer, int maxTokens, BiConsumer<Integer, Token> whenMaxTokensExceeded) {
        this.lexer = lexer;
        this.maxTokens = maxTokens;
        this.whenMaxTokensExceeded = whenMaxTokensExceeded;
        // We have channels 0, 2, and 3 - use 5 for safety
        this.channelCounts = new int[]{0, 0, 0, 0, 0};
    }

    @Override
    public Token nextToken() {
        Token token = lexer.nextToken();
        if (token != null) {
            int channel = token.getChannel();
            int currentCount = ++channelCounts[channel];
            callbackIfMaxExceeded(maxTokens, currentCount, token);
        }
        return token;
    }

    private void callbackIfMaxExceeded(int maxCount, int currentCount, Token token) {
        if (currentCount > maxCount) {
            whenMaxTokensExceeded.accept(maxCount, token);
        }
    }

    @Override
    public int getLine() {
        return lexer.getLine();
    }

    @Override
    public int getCharPositionInLine() {
        return lexer.getCharPositionInLine();
    }

    @Override
    public CharStream getInputStream() {
        return lexer.getInputStream();
    }

    @Override
    public String getSourceName() {
        return lexer.getSourceName();
    }

    @Override
    public void setTokenFactory(TokenFactory<?> factory) {
        lexer.setTokenFactory(factory);
    }

    @Override
    public TokenFactory<?> getTokenFactory() {
        return lexer.getTokenFactory();
    }
}
