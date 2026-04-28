package dev.feddi.federation.engine.graph;

import dev.feddi.federation.engine.parser.FieldSelectionMap.Path;
import dev.feddi.federation.engine.parser.FieldSelectionMap.SelectedValue;
import dev.feddi.federation.engine.parser.FieldSelectionMapParser;
import graphql.language.Type;

import java.util.List;

/**
 * Represents a lookup argument mapping from an @is directive.
 *
 * The @is directive maps a @lookup field argument to source fields on the entity.
 * This tells the feddi Gateway which field values to pass when invoking the lookup.
 *
 * For simple cases like `productById(id: ID! @is(field: "id")): Product @lookup`:
 * - argumentName = "id" (the argument name on the lookup field)
 * - selection contains a Path for "id"
 *
 * For composite keys like `@is(field: "{ sku category brand }")`:
 * - selection contains an ObjectSelection with multiple fields
 *
 * @param argumentName the name of the argument on the lookup field
 * @param selection the parsed FieldSelectionMap value (from @is directive's "field" value)
 * @param argumentType the GraphQL AST type of the argument
 */
public record LookupArgument(
    String argumentName,
    SelectedValue selection,
    Type<?> argumentType
) {
    public LookupArgument {
        if (argumentName == null || argumentName.isBlank()) {
            throw new IllegalArgumentException("argumentName cannot be null or blank");
        }
        if (selection == null) {
            throw new IllegalArgumentException("selection cannot be null");
        }
        // argumentType can be null if type information is not available
    }

    /**
     * Creates a LookupArgument from a string field selection, without type information.
     */
    public static LookupArgument of(String argumentName, String fieldSelection) {
        return new LookupArgument(argumentName, parseSelection(fieldSelection), null);
    }

    /**
     * Creates a LookupArgument from a string field selection, with type information.
     */
    public static LookupArgument of(String argumentName, String fieldSelection, Type<?> argumentType) {
        return new LookupArgument(argumentName, parseSelection(fieldSelection), argumentType);
    }

    /**
     * Creates a LookupArgument from a pre-parsed SelectedValue, with type information.
     */
    public static LookupArgument of(String argumentName, SelectedValue selection, Type<?> argumentType) {
        return new LookupArgument(argumentName, selection, argumentType);
    }

    /**
     * Creates a LookupArgument from a Path, with type information.
     * Convenience method for simple path cases.
     */
    public static LookupArgument of(String argumentName, Path path, Type<?> argumentType) {
        return new LookupArgument(argumentName, new SelectedValue(path), argumentType);
    }

    /**
     * Returns the path if this is a simple path selection.
     * For complex selections (object selections, lists), returns the first path found.
     *
     * @return the path for simple selections
     * @throws IllegalStateException if no path can be extracted
     */
    public Path path() {
        List<Path> paths = extractPaths();
        if (paths.isEmpty()) {
            throw new IllegalStateException("No paths found in selection for argument: " + argumentName);
        }
        return paths.get(0);
    }

    /**
     * Checks if this is a simple path selection (single path, no object/list selection).
     */
    public boolean isSimplePath() {
        return selection.alternatives().size() == 1
            && selection.alternatives().get(0) instanceof Path;
    }

    public List<Path> extractPaths() {
        return selection.extractPaths();
    }

    private static SelectedValue parseSelection(String fieldSelection) {
        if (fieldSelection == null || fieldSelection.isBlank()) {
            throw new IllegalArgumentException("fieldSelection cannot be null or blank");
        }
        return FieldSelectionMapParser.parseFieldSelectionMap(fieldSelection);
    }
}
