package dev.feddi.federation.engine.parser.exceptions;

import dev.feddi.federation.engine.parser.InvalidSyntaxException;

/**
 * Exception thrown when parsing is cancelled due to too many characters.
 */
public class ParseCancelledTooManyCharsException extends InvalidSyntaxException {

    public ParseCancelledTooManyCharsException(int maxCharacters) {
        super("Parsing cancelled: exceeded maximum of " + maxCharacters + " characters",
                null, null, null, null);
    }
}
