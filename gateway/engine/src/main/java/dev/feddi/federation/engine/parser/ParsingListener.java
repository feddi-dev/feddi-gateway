package dev.feddi.federation.engine.parser;

/**
 * Listener interface invoked for each token parsed by the FieldSelectionMap parser.
 */
public interface ParsingListener {

    /**
     * A NoOp implementation of {@link ParsingListener}
     */
    ParsingListener NOOP = t -> {
    };

    /**
     * Represents a token that has been parsed
     */
    interface Token {
        /**
         * @return the text of the parsed token
         */
        String getText();

        /**
         * @return the line the token occurred on
         */
        int getLine();

        /**
         * @return the position within the line the token occurred on
         */
        int getCharPositionInLine();
    }

    /**
     * Called for each token found during parsing
     *
     * @param token the token found
     */
    void onToken(Token token);
}
