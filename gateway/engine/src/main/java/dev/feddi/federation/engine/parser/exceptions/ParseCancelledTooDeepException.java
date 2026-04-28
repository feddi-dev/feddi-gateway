package dev.feddi.federation.engine.parser.exceptions;

import dev.feddi.federation.engine.parser.InvalidSyntaxException;
import dev.feddi.federation.engine.parser.SourceLocation;

/**
 * Exception thrown when parsing is cancelled due to excessive rule depth.
 */
public class ParseCancelledTooDeepException extends InvalidSyntaxException {

    public ParseCancelledTooDeepException(SourceLocation sourceLocation, String offendingToken, int maxDepth, String tokenType) {
        super("Parsing cancelled: exceeded maximum rule depth of " + maxDepth,
                sourceLocation, offendingToken, null, null);
    }
}
