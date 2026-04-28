package dev.feddi.federation.engine.parser.exceptions;

import dev.feddi.federation.engine.parser.InvalidSyntaxException;
import dev.feddi.federation.engine.parser.SourceLocation;

/**
 * Exception thrown when there are more tokens than expected after parsing.
 */
public class MoreTokensSyntaxException extends InvalidSyntaxException {

    public MoreTokensSyntaxException(SourceLocation sourceLocation, String offendingToken, String sourcePreview) {
        super("Invalid syntax: unexpected tokens after end of expression",
                sourceLocation, offendingToken, sourcePreview, null);
    }

    public MoreTokensSyntaxException(SourceLocation sourceLocation) {
        super("Invalid syntax: unexpected tokens after end of expression",
                sourceLocation, null, null, null);
    }
}
