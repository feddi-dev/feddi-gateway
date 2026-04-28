package dev.feddi.federation.engine.parser.exceptions;

import dev.feddi.federation.engine.parser.InvalidSyntaxException;
import dev.feddi.federation.engine.parser.SourceLocation;

/**
 * Exception thrown when parsing is cancelled due to too many tokens.
 */
public class ParseCancelledException extends InvalidSyntaxException {

    public ParseCancelledException(SourceLocation sourceLocation, String offendingToken, int maxTokens, String tokenType) {
        super("Parsing cancelled: exceeded maximum of " + maxTokens + " " + tokenType + " tokens",
                sourceLocation, offendingToken, null, null);
    }
}
