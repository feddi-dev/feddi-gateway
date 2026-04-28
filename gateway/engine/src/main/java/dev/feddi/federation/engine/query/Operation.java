package dev.feddi.federation.engine.query;

import dev.feddi.federation.engine.Constants;
import graphql.language.Definition;
import graphql.language.Document;
import graphql.language.Field;
import graphql.language.InlineFragment;
import graphql.language.OperationDefinition;
import graphql.language.SelectionSet;
import graphql.language.VariableDefinition;
import graphql.parser.Parser;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a parsed operation to be planned.
 *
 * @param rootType the root type (typically "Query" or "Mutation")
 * @param variableDefinitions the variable definitions from the operation
 * @param selections the top-level selections
 */
public record Operation(
    String rootType,
    List<VariableDefinition> variableDefinitions,
    List<Selection> selections
) {

    public Operation {
        if (rootType == null || rootType.isBlank()) {
            throw new IllegalArgumentException("rootType cannot be null or blank");
        }
        variableDefinitions = variableDefinitions == null ? List.of() : List.copyOf(variableDefinitions);
        selections = selections == null ? List.of() : List.copyOf(selections);
    }

    /**
     * Convenience constructor without variable definitions.
     */
    public Operation(String rootType, List<Selection> selections) {
        this(rootType, List.of(), selections);
    }

    /**
     * Creates an Operation with the given selections.
     */
    public static Operation of(String rootType, Selection... selections) {
        return new Operation(rootType, List.of(), List.of(selections));
    }

    /**
     * Creates an Operation with the given selections.
     */
    public static Operation of(String rootType, List<Selection> selections) {
        return new Operation(rootType, List.of(), selections);
    }

    /**
     * Parses a GraphQL query string into an Operation model.
     *
     * @param queryString the GraphQL query string (e.g., "{ users { id name } }")
     * @return Operation model with root type and selections
     * @throws OperationParseException if the query cannot be parsed
     */
    public static Operation parse(String queryString) {
        return parse(queryString, null);
    }

    /**
     * Parses a GraphQL query string into an Operation model with optional normalization.
     *
     * @param queryString the GraphQL query string (e.g., "{ users { id name } }")
     * @param normalizer the normalizer to apply to parsed documents, or null for no normalization
     * @return Operation model with root type and selections
     * @throws OperationParseException if the query cannot be parsed
     */
    public static Operation parse(String queryString, OperationNormalizer normalizer) {
        if (queryString == null || queryString.isBlank()) {
            throw new OperationParseException("Query string cannot be null or blank");
        }

        try {
            Document document = new Parser().parseDocument(queryString);

            if (normalizer != null) {
                document = normalizer.normalize(document);
            }

            OperationDefinition operationDef = findOperationDefinition(document);
            return fromOperationDefinition(operationDef);

        } catch (OperationParseException e) {
            throw e;
        } catch (Exception e) {
            throw new OperationParseException("Failed to parse GraphQL query: " + e.getMessage(), e);
        }
    }

    private static OperationDefinition findOperationDefinition(Document document) {
        for (Definition<?> definition : document.getDefinitions()) {
            if (definition instanceof OperationDefinition opDef) {
                return opDef;
            }
        }
        throw new OperationParseException("No operation definition found in query");
    }

    /**
     * Creates an Operation from a GraphQL OperationDefinition.
     *
     * @param operationDef the parsed operation definition
     * @return the Operation model
     */
    public static Operation fromOperationDefinition(OperationDefinition operationDef) {
        String rootType = switch (operationDef.getOperation()) {
            case QUERY -> Constants.QUERY;
            case MUTATION -> Constants.MUTATION;
            case SUBSCRIPTION -> Constants.SUBSCRIPTION;
        };

        List<VariableDefinition> varDefs = operationDef.getVariableDefinitions();
        List<Selection> selections = parseSelectionSet(operationDef.getSelectionSet());

        return new Operation(rootType, varDefs, selections);
    }

    /**
     * Parses a SelectionSet into a list of Selections.
     */
    private static List<Selection> parseSelectionSet(SelectionSet selectionSet) {
        if (selectionSet == null || selectionSet.getSelections().isEmpty()) {
            return List.of();
        }

        List<Selection> selections = new ArrayList<>();
        for (graphql.language.Selection<?> selection : selectionSet.getSelections()) {
            if (selection instanceof Field field) {
                List<Selection> subSelections = parseSelectionSet(field.getSelectionSet());
                selections.add(new FieldSelection(
                    field.getAlias(),
                    field.getName(),
                    field.getArguments(),
                    field.getDirectives(),
                    subSelections
                ));
            } else if (selection instanceof InlineFragment inlineFragment) {
                String typeCondition = inlineFragment.getTypeCondition() != null
                    ? inlineFragment.getTypeCondition().getName()
                    : null;
                List<Selection> subSelections = parseSelectionSet(inlineFragment.getSelectionSet());
                selections.add(new InlineFragmentSelection(
                    typeCondition,
                    inlineFragment.getDirectives(),
                    subSelections
                ));
            }
            // Note: FragmentSpreads should be inlined before parsing
        }
        return selections;
    }

    /**
     * Returns only the field selections (excludes inline fragments).
     * Useful for tests and code that only expects fields at top level.
     */
    public List<FieldSelection> fieldSelections() {
        return selections.stream()
            .filter(s -> s instanceof FieldSelection)
            .map(s -> (FieldSelection) s)
            .toList();
    }

    @Override
    public String toString() {
        return String.format("query { %s }",
            String.join(" ", selections.stream().map(Object::toString).toList()));
    }

    /**
     * Exception thrown when a GraphQL operation cannot be parsed.
     */
    public static class OperationParseException extends RuntimeException {
        public OperationParseException(String message) {
            super(message);
        }

        public OperationParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
