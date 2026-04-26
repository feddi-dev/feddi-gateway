package dev.feddi.federation.engine.parser;

/**
 * Represents a location in source text.
 */
public record SourceLocation(int line, int column, String sourceName) {
    public static final SourceLocation EMPTY = new SourceLocation(0, 0, null);
}
