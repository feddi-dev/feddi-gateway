package dev.feddi.federation.engine.parser;

import org.antlr.v4.runtime.Token;

/**
 * Helper utilities for ANTLR parsing.
 */
public class AntlrHelper {

    public static SourceLocation createSourceLocation(Token token) {
        return new SourceLocation(
                token.getLine(),
                token.getCharPositionInLine() + 1,
                null
        );
    }
}
