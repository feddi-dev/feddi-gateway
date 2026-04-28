package dev.feddi.federation.engine.parser;

import dev.feddi.federation.engine.parser.exceptions.MoreTokensSyntaxException;
import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.ParseCancellationException;

import java.io.Reader;

/**
 * Extended bail error strategy that provides better error messages.
 */
public class ExtendedBailStrategy extends BailErrorStrategy {
    private final Reader reader;
    private final ParserOptions parserOptions;

    public ExtendedBailStrategy(Reader reader, ParserOptions parserOptions) {
        this.reader = reader;
        this.parserOptions = parserOptions;
    }

    @Override
    public void recover(Parser recognizer, RecognitionException e) {
        try {
            super.recover(recognizer, e);
        } catch (ParseCancellationException parseException) {
            throw mkException(recognizer, e);
        }
    }

    @Override
    public Token recoverInline(Parser recognizer) throws RecognitionException {
        try {
            return super.recoverInline(recognizer);
        } catch (ParseCancellationException parseException) {
            throw mkException(recognizer, null);
        }
    }

    InvalidSyntaxException mkMoreTokensException(Token token) {
        SourceLocation sourceLocation = AntlrHelper.createSourceLocation(token);
        return new MoreTokensSyntaxException(sourceLocation);
    }

    private InvalidSyntaxException mkException(Parser recognizer, RecognitionException cause) {
        String offendingToken;
        final SourceLocation sourceLocation;
        Token currentToken = recognizer.getCurrentToken();
        if (currentToken != null) {
            sourceLocation = AntlrHelper.createSourceLocation(currentToken);
            offendingToken = currentToken.getText();
        } else {
            offendingToken = null;
            sourceLocation = null;
        }

        String msgKey;
        if (offendingToken == null) {
            msgKey = "Invalid syntax: no token available";
        } else {
            msgKey = "Invalid syntax at '" + offendingToken + "'";
        }
        return new InvalidSyntaxException(msgKey, sourceLocation, offendingToken, null, cause);
    }
}
