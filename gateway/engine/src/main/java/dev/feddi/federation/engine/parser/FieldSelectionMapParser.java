package dev.feddi.federation.engine.parser;

import dev.feddi.federation.engine.parser.FieldSelectionMap.FieldSelectionSet;
import dev.feddi.federation.engine.parser.FieldSelectionMap.SelectedValue;
import dev.feddi.federation.engine.parser.antlr.FSMBaseListener;
import dev.feddi.federation.engine.parser.antlr.FSMLexer;
import dev.feddi.federation.engine.parser.antlr.FSMParser;
import dev.feddi.federation.engine.parser.exceptions.ParseCancelledException;
import dev.feddi.federation.engine.parser.exceptions.ParseCancelledTooDeepException;
import dev.feddi.federation.engine.parser.exceptions.ParseCancelledTooManyCharsException;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CodePointCharStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Parser for FieldSelectionMap and FieldSelectionSet scalar values.
 *
 * FieldSelectionMap (SelectedValue) is used by @is and @require directives.
 * FieldSelectionSet is used by @key and @provides directives.
 */
public class FieldSelectionMapParser {

    /**
     * Parses a FieldSelectionMap string into a SelectedValue AST.
     * Used for @is and @require directives.
     *
     * @param input the FieldSelectionMap string to parse
     * @return the parsed SelectedValue
     * @throws InvalidSyntaxException if the input is not valid
     */
    public static SelectedValue parseFieldSelectionMap(String input) throws InvalidSyntaxException {
        StringReader reader = new StringReader(input);
        ParserOptions parserOptions = ParserOptions.getDefaultParserOptions();
        return new FieldSelectionMapParser().parseSelectedValue(reader, parserOptions);
    }

    /**
     * Parses a FieldSelectionSet string into a FieldSelectionSet AST.
     * Used for @key and @provides directives.
     *
     * Supports GraphQL-like selection set syntax:
     * - Simple: "id name"
     * - Nested: "author { id name }"
     * - Mixed: "id author { name books { title } }"
     *
     * @param input the FieldSelectionSet string to parse
     * @return the parsed FieldSelectionSet
     * @throws InvalidSyntaxException if the input is not valid
     */
    public static FieldSelectionSet parseFieldSelectionSet(String input) throws InvalidSyntaxException {
        StringReader reader = new StringReader(input);
        ParserOptions parserOptions = ParserOptions.getDefaultParserOptions();
        return new FieldSelectionMapParser().parseFieldSelectionSetImpl(reader, parserOptions);
    }

    private SelectedValue parseSelectedValue(Reader reader, ParserOptions parserOptions) throws InvalidSyntaxException {
        ParseContext ctx = setupParser(reader, parserOptions);

        // Parsing starts
        FSMParser.SelectedValueContext selectedValueContext = ctx.parser.selectedValue();
        SelectedValue selectedValue = ctx.toLanguage.createSelectedValue(selectedValueContext);

        checkForUnconsumedTokens(selectedValueContext, ctx);
        return selectedValue;
    }

    private FieldSelectionSet parseFieldSelectionSetImpl(Reader reader, ParserOptions parserOptions) throws InvalidSyntaxException {
        ParseContext ctx = setupParser(reader, parserOptions);

        // Parsing starts
        FSMParser.FieldSelectionSetContext fieldSelectionSetContext = ctx.parser.fieldSelectionSet();
        FieldSelectionSet fieldSelectionSet = ctx.toLanguage.createFieldSelectionSet(fieldSelectionSetContext);

        checkForUnconsumedTokens(fieldSelectionSetContext, ctx);
        return fieldSelectionSet;
    }

    private record ParseContext(
        FSMParser parser,
        CommonTokenStream tokens,
        FSMAntlrToLanguage toLanguage,
        ExtendedBailStrategy bailStrategy
    ) {}

    private ParseContext setupParser(Reader reader, ParserOptions parserOptions) {
        SafeTokenReader safeTokenReader = setupSafeTokenReader(parserOptions, reader);
        CodePointCharStream charStream = setupCharStream(safeTokenReader);
        FSMLexer lexer = setupFSMLexer(parserOptions, reader, charStream);

        // This lexer wrapper allows us to stop lexing when too many tokens are in place
        SafeTokenSource safeTokenSource = getSafeTokenSource(parserOptions, reader, lexer);

        CommonTokenStream tokens = new CommonTokenStream(safeTokenSource);

        FSMParser antlrParser = new FSMParser(tokens);
        antlrParser.removeErrorListeners();
        antlrParser.getInterpreter().setPredictionMode(PredictionMode.SLL);

        ExtendedBailStrategy bailStrategy = new ExtendedBailStrategy(reader, parserOptions);
        antlrParser.setErrorHandler(bailStrategy);

        FSMAntlrToLanguage toLanguage = new FSMAntlrToLanguage(tokens, reader, parserOptions);

        setupParserListener(reader, antlrParser, toLanguage);

        return new ParseContext(antlrParser, tokens, toLanguage, bailStrategy);
    }

    private void checkForUnconsumedTokens(ParserRuleContext ruleContext, ParseContext ctx) throws InvalidSyntaxException {
        Token stop = ruleContext.getStop();
        List<Token> allTokens = ctx.tokens.getTokens();
        if (stop != null && allTokens != null && !allTokens.isEmpty()) {
            Token last = allTokens.get(allTokens.size() - 1);
            // Check if we have more tokens in the stream than we consumed
            boolean notEOF = last.getType() != Token.EOF;
            boolean lastGreaterThanDocument = last.getTokenIndex() > stop.getTokenIndex();
            boolean sameChannel = last.getChannel() == stop.getChannel();
            if (notEOF && lastGreaterThanDocument && sameChannel) {
                throw ctx.bailStrategy.mkMoreTokensException(last);
            }
        }
    }

    private static SafeTokenReader setupSafeTokenReader(ParserOptions parserOptions, Reader reader) {
        int maxCharacters = parserOptions.getMaxCharacters();
        Consumer<Integer> onTooManyCharacters = it -> {
            throw new ParseCancelledTooManyCharsException(maxCharacters);
        };
        return new SafeTokenReader(reader, maxCharacters, onTooManyCharacters);
    }

    private static CodePointCharStream setupCharStream(SafeTokenReader safeTokenReader) {
        CodePointCharStream charStream;
        try {
            charStream = CharStreams.fromReader(safeTokenReader);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return charStream;
    }

    private static FSMLexer setupFSMLexer(ParserOptions parserOptions, Reader reader, CodePointCharStream charStream) {
        FSMLexer lexer = new FSMLexer(charStream);
        lexer.removeErrorListeners();
        lexer.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine, String antlerMsg, RecognitionException e) {
                SourceLocation sourceLocation = new SourceLocation(line, charPositionInLine + 1, null);
                throw new InvalidSyntaxException("Invalid syntax: " + antlerMsg, sourceLocation, null, null, null);
            }
        });
        return lexer;
    }

    private SafeTokenSource getSafeTokenSource(ParserOptions parserOptions, Reader reader, FSMLexer lexer) {
        int maxTokens = parserOptions.getMaxTokens();
        BiConsumer<Integer, Token> onTooManyTokens = (maxTokenCount, token) -> throwIfTokenProblems(
            parserOptions,
            token,
            maxTokenCount,
            reader,
            ParseCancelledException.class);
        return new SafeTokenSource(lexer, maxTokens, onTooManyTokens);
    }

    private void setupParserListener(Reader reader, FSMParser parser, FSMAntlrToLanguage toLanguage) {
        ParserOptions parserOptions = toLanguage.getParserOptions();
        ParsingListener parsingListener = parserOptions.getParsingListener();
        int maxTokens = parserOptions.getMaxTokens();
        int maxRuleDepth = parserOptions.getMaxRuleDepth();

        // Prevent attacks by restricting tokens and rule depth
        FSMBaseListener listener = new FSMBaseListener() {
            int count = 0;
            int depth = 0;

            @Override
            public void enterEveryRule(ParserRuleContext ctx) {
                depth++;
                if (depth > maxRuleDepth) {
                    throwIfTokenProblems(
                        parserOptions,
                        ctx.getStart(),
                        maxRuleDepth,
                        reader,
                        ParseCancelledTooDeepException.class
                    );
                }
            }

            @Override
            public void exitEveryRule(ParserRuleContext ctx) {
                depth--;
            }

            @Override
            public void visitTerminal(TerminalNode node) {
                final Token token = node.getSymbol();
                parsingListener.onToken(new ParsingListener.Token() {
                    @Override
                    public String getText() {
                        return token == null ? null : token.getText();
                    }

                    @Override
                    public int getLine() {
                        return token == null ? -1 : token.getLine();
                    }

                    @Override
                    public int getCharPositionInLine() {
                        return token == null ? -1 : token.getCharPositionInLine();
                    }
                });

                count++;
                if (count > maxTokens) {
                    throwIfTokenProblems(
                        parserOptions,
                        token,
                        maxTokens,
                        reader,
                        ParseCancelledException.class
                    );
                }
            }
        };
        parser.addParseListener(listener);
    }

    private void throwIfTokenProblems(ParserOptions parserOptions, Token token, int maxLimit, Reader reader, Class<? extends InvalidSyntaxException> targetException) throws ParseCancelledException {
        String tokenType = "grammar";
        SourceLocation sourceLocation = null;
        String offendingToken = null;
        if (token != null) {
            offendingToken = token.getText();
            sourceLocation = new SourceLocation(token.getLine(), token.getCharPositionInLine() + 1, null);
        }
        if (targetException.equals(ParseCancelledTooDeepException.class)) {
            throw new ParseCancelledTooDeepException(sourceLocation, offendingToken, maxLimit, tokenType);
        }
        throw new ParseCancelledException(sourceLocation, offendingToken, maxLimit, tokenType);
    }
}
