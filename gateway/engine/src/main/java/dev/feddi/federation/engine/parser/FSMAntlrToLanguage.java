package dev.feddi.federation.engine.parser;

import dev.feddi.federation.engine.parser.FieldSelectionMap.FieldSelection;
import dev.feddi.federation.engine.parser.FieldSelectionMap.FieldSelectionSet;
import dev.feddi.federation.engine.parser.FieldSelectionMap.InlineFragment;
import dev.feddi.federation.engine.parser.FieldSelectionMap.ListSelection;
import dev.feddi.federation.engine.parser.FieldSelectionMap.Path;
import dev.feddi.federation.engine.parser.FieldSelectionMap.SelectionItem;
import dev.feddi.federation.engine.parser.antlr.FSMParser.FieldSelectionContext;
import dev.feddi.federation.engine.parser.antlr.FSMParser.FieldSelectionSetContext;
import dev.feddi.federation.engine.parser.antlr.FSMParser.InlineFragmentContext;
import dev.feddi.federation.engine.parser.antlr.FSMParser.PathContext;
import dev.feddi.federation.engine.parser.antlr.FSMParser.SelectionItemContext;
import dev.feddi.federation.engine.parser.antlr.FSMParser.SelectionSetContext;
import org.antlr.v4.runtime.CommonTokenStream;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

import static dev.feddi.federation.engine.parser.FieldSelectionMap.Alternative;
import static dev.feddi.federation.engine.parser.FieldSelectionMap.ObjectField;
import static dev.feddi.federation.engine.parser.FieldSelectionMap.ObjectSelection;
import static dev.feddi.federation.engine.parser.FieldSelectionMap.PathSegment;
import static dev.feddi.federation.engine.parser.FieldSelectionMap.SelectedValue;
import static dev.feddi.federation.engine.parser.antlr.FSMParser.PathSegmentContext;
import static dev.feddi.federation.engine.parser.antlr.FSMParser.SelectedListValueContext;
import static dev.feddi.federation.engine.parser.antlr.FSMParser.SelectedObjectFieldContext;
import static dev.feddi.federation.engine.parser.antlr.FSMParser.SelectedObjectValueContext;
import static dev.feddi.federation.engine.parser.antlr.FSMParser.SelectedValueContext;
import static dev.feddi.federation.engine.parser.antlr.FSMParser.SelectedValueEntryContext;
import static java.util.Optional.ofNullable;

/**
 * Converts ANTLR parse tree to FieldSelectionMap and FieldSelectionSet AST.
 */
public class FSMAntlrToLanguage {

    private final CommonTokenStream tokens;
    private final Reader reader;
    private final ParserOptions parserOptions;

    public FSMAntlrToLanguage(CommonTokenStream tokens,
                              Reader reader,
                              ParserOptions parserOptions) {
        this.tokens = tokens;
        this.reader = reader;
        this.parserOptions = ofNullable(parserOptions).orElse(ParserOptions.getDefaultParserOptions());
    }

    public ParserOptions getParserOptions() {
        return parserOptions;
    }

    public SelectedValue createSelectedValue(SelectedValueContext ctx) {
        if (ctx == null || ctx.selectedValueEntry() == null || ctx.selectedValueEntry().isEmpty()) {
            return SelectedValue.empty();
        }
        List<Alternative> alternatives = ctx.selectedValueEntry()
            .stream()
            .map(this::createAlternativeFromEntry)
            .toList();
        return new SelectedValue(alternatives);
    }

    private Alternative createAlternativeFromEntry(SelectedValueEntryContext entryCtx) {
        PathContext pathCtx = entryCtx.path();
        SelectedObjectValueContext objValCtx = entryCtx.selectedObjectValue();
        SelectedListValueContext listValCtx = entryCtx.selectedListValue();
        Path pathPrefix = null;
        if (pathCtx != null) {
            pathPrefix = createPath(pathCtx);
        }
        if (objValCtx != null) {
            List<ObjectField> fields = createObjectFields(objValCtx);
            return new ObjectSelection(pathPrefix, fields);
        }
        if (listValCtx != null) {
            SelectedValue elementValue = parseListElementSelectedValue(listValCtx);
            return new ListSelection(pathPrefix, elementValue);
        }
        if (pathPrefix != null) {
            return pathPrefix;
        }
        throw new IllegalStateException("Unhandled SelectedValueEntryContext structure or empty entry: " + entryCtx.getText());
    }

    private SelectedValue parseListElementSelectedValue(SelectedListValueContext listCtx) {
        if (listCtx.selectedValue() != null) {
            return createSelectedValue(listCtx.selectedValue());
        }
        if (listCtx.selectedListValue() != null) {
            return new SelectedValue(
                new ListSelection(parseListElementSelectedValue(listCtx.selectedListValue()))
            );
        }
        throw new IllegalStateException("SelectedListValueContext is malformed. Expected selectedValue child. Got: " + listCtx.getText());
    }

    private List<ObjectField> createObjectFields(SelectedObjectValueContext objValCtx) {
        if (objValCtx == null || objValCtx.selectedObjectField() == null || objValCtx.selectedObjectField().isEmpty()) {
            return List.of();
        }
        List<ObjectField> fieldList = new ArrayList<>();
        for (SelectedObjectFieldContext fieldCtx : objValCtx.selectedObjectField()) {
            String name = fieldCtx.name().getText();
            SelectedValue value;
            if (fieldCtx.selectedValue() != null) {
                value = createSelectedValue(fieldCtx.selectedValue());
            } else {
                value = new SelectedValue(Path.of(name));
            }
            fieldList.add(new ObjectField(name, value));
        }
        return fieldList;
    }

    private Path createPath(PathContext pathCtx) {
        List<PathSegment> segments = new ArrayList<>();
        PathSegmentContext currentAntlrSegmentCtx = pathCtx.pathSegment();

        // Initial type condition: '<Foo>' in '<Foo>.bar'
        // This is the type condition BEFORE the first field - it specifies the lookup context
        String initialTypeCondition = pathCtx.typeName() != null ? pathCtx.typeName().getText() : null;

        while (currentAntlrSegmentCtx != null) {
            String fieldName = currentAntlrSegmentCtx.fieldName().getText();
            // Infix type condition: '<Foo>' in 'bar<Foo>.baz'
            // This is the type condition AFTER a field - it specifies the return type (type narrowing)
            String infixTypeCondition = currentAntlrSegmentCtx.typeName() != null
                ? currentAntlrSegmentCtx.typeName().getText()
                : null;
            segments.add(new PathSegment(fieldName, infixTypeCondition));
            currentAntlrSegmentCtx = currentAntlrSegmentCtx.pathSegment();
        }
        return new Path(initialTypeCondition, segments);
    }

    // ========================================================================
    // FieldSelectionSet conversion (for @key and @provides)
    // ========================================================================

    public FieldSelectionSet createFieldSelectionSet(FieldSelectionSetContext ctx) {
        if (ctx == null || ctx.selectionItem() == null || ctx.selectionItem().isEmpty()) {
            return new FieldSelectionSet(List.of());
        }
        List<SelectionItem> items = ctx.selectionItem()
            .stream()
            .map(this::createSelectionItem)
            .toList();
        return new FieldSelectionSet(items);
    }

    private SelectionItem createSelectionItem(SelectionItemContext ctx) {
        if (ctx.fieldSelection() != null) {
            return createFieldSelection(ctx.fieldSelection());
        } else if (ctx.inlineFragment() != null) {
            return createInlineFragment(ctx.inlineFragment());
        }
        throw new IllegalStateException("Unknown selection item type: " + ctx.getText());
    }

    private FieldSelection createFieldSelection(FieldSelectionContext ctx) {
        String fieldName = ctx.fieldName().getText();
        SelectionSetContext selSetCtx = ctx.selectionSet();
        if (selSetCtx != null && selSetCtx.selectionItem() != null && !selSetCtx.selectionItem().isEmpty()) {
            List<SelectionItem> subSelections = selSetCtx.selectionItem()
                .stream()
                .map(this::createSelectionItem)
                .toList();
            return new FieldSelection(fieldName, subSelections);
        }
        return new FieldSelection(fieldName);
    }

    private InlineFragment createInlineFragment(InlineFragmentContext ctx) {
        String typeName = ctx.typeName().getText();
        SelectionSetContext selSetCtx = ctx.selectionSet();
        if (selSetCtx != null && selSetCtx.selectionItem() != null && !selSetCtx.selectionItem().isEmpty()) {
            List<SelectionItem> selections = selSetCtx.selectionItem()
                .stream()
                .map(this::createSelectionItem)
                .toList();
            return new InlineFragment(typeName, selections);
        }
        return new InlineFragment(typeName, List.of());
    }
}
