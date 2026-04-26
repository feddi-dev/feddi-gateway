package dev.feddi.federation.engine.parser;

/**
 * Exception thrown when the FieldSelectionMap parser encounters invalid syntax.
 */
public class InvalidSyntaxException extends RuntimeException {

    private final String message;
    private final String sourcePreview;
    private final String offendingToken;
    private final SourceLocation location;

    protected InvalidSyntaxException(String msg, SourceLocation location, String offendingToken, String sourcePreview, Exception cause) {
        super(msg, cause);
        this.message = msg;
        this.sourcePreview = sourcePreview;
        this.offendingToken = offendingToken;
        this.location = location;
    }

    @Override
    public String getMessage() {
        return message;
    }

    public SourceLocation getLocation() {
        return location;
    }

    public String getSourcePreview() {
        return sourcePreview;
    }

    public String getOffendingToken() {
        return offendingToken;
    }
}
