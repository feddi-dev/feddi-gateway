package dev.feddi.federation.engine.parser;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Options that control how the FieldSelectionMapParser behaves.
 */
public class ParserOptions {
    /**
     * Maximum number of characters allowed (1 MB by default) to prevent DOS attacks.
     */
    public static final int MAX_CHARACTERS = 1024 * 1024;

    /**
     * Maximum number of tokens allowed (15000 by default) to prevent DOS attacks.
     */
    public static final int MAX_TOKENS = 15_000;

    /**
     * Maximum rule depth allowed (500 by default) to prevent stack overflow.
     */
    public static final int MAX_RULE_DEPTH = 500;

    private static ParserOptions defaultJvmParserOptions = newParserOptions()
            .maxCharacters(MAX_CHARACTERS)
            .maxTokens(MAX_TOKENS)
            .maxRuleDepth(MAX_RULE_DEPTH)
            .build();

    /**
     * @return the static default JVM parser options
     */
    public static ParserOptions getDefaultParserOptions() {
        return defaultJvmParserOptions;
    }

    /**
     * Sets the default JVM parser options.
     *
     * @param options the new default JVM parser options
     */
    public static void setDefaultParserOptions(ParserOptions options) {
        defaultJvmParserOptions = Objects.requireNonNull(options);
    }

    private final int maxCharacters;
    private final int maxTokens;
    private final int maxRuleDepth;
    private final ParsingListener parsingListener;

    private ParserOptions(Builder builder) {
        this.maxCharacters = builder.maxCharacters;
        this.maxTokens = builder.maxTokens;
        this.maxRuleDepth = builder.maxRuleDepth;
        this.parsingListener = builder.parsingListener;
    }

    /**
     * @return the maximum number of characters the parser will accept
     */
    public int getMaxCharacters() {
        return maxCharacters;
    }

    /**
     * @return the maximum number of tokens the parser will accept
     */
    public int getMaxTokens() {
        return maxTokens;
    }

    /**
     * @return the maximum rule depth the parser will accept
     */
    public int getMaxRuleDepth() {
        return maxRuleDepth;
    }

    /**
     * @return the parsing listener
     */
    public ParsingListener getParsingListener() {
        return parsingListener;
    }

    public ParserOptions transform(Consumer<Builder> builderConsumer) {
        Builder builder = new Builder(this);
        builderConsumer.accept(builder);
        return builder.build();
    }

    public static Builder newParserOptions() {
        return new Builder();
    }

    public static class Builder {

        private ParsingListener parsingListener = ParsingListener.NOOP;
        private int maxCharacters = MAX_CHARACTERS;
        private int maxTokens = MAX_TOKENS;
        private int maxRuleDepth = MAX_RULE_DEPTH;

        Builder() {
        }

        Builder(ParserOptions parserOptions) {
            this.maxCharacters = parserOptions.maxCharacters;
            this.maxTokens = parserOptions.maxTokens;
            this.maxRuleDepth = parserOptions.maxRuleDepth;
            this.parsingListener = parserOptions.parsingListener;
        }

        public Builder maxCharacters(int maxCharacters) {
            this.maxCharacters = maxCharacters;
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder maxRuleDepth(int maxRuleDepth) {
            this.maxRuleDepth = maxRuleDepth;
            return this;
        }

        public Builder parsingListener(ParsingListener parsingListener) {
            this.parsingListener = Objects.requireNonNull(parsingListener);
            return this;
        }

        public ParserOptions build() {
            return new ParserOptions(this);
        }
    }
}
