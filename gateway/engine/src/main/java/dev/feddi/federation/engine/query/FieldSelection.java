package dev.feddi.federation.engine.query;

import graphql.language.Argument;

import java.util.List;

/**
 * Represents a field selection in a query.
 * Can contain nested sub-selections for object fields.
 *
 * @param alias the alias for this field (null if no alias)
 * @param fieldName the name of the field being selected
 * @param arguments the arguments passed to this field (empty if none)
 * @param subSelections nested selections (empty for leaf fields)
 */
public record FieldSelection(
    String alias,
    String fieldName,
    List<Argument> arguments,
    List<Selection> subSelections
) implements Selection {

    public FieldSelection {
        if (fieldName == null || fieldName.isBlank()) {
            throw new IllegalArgumentException("fieldName cannot be null or blank");
        }
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
        subSelections = subSelections == null ? List.of() : List.copyOf(subSelections);
    }

    /**
     * Convenience constructor without alias or arguments.
     */
    public FieldSelection(String fieldName, List<Selection> subSelections) {
        this(null, fieldName, List.of(), subSelections);
    }

    /**
     * Returns the response key for this field (alias if present, otherwise field name).
     */
    public String responseKey() {
        return alias != null ? alias : fieldName;
    }

    /**
     * Checks if this field has an alias.
     */
    public boolean hasAlias() {
        return alias != null;
    }

    /**
     * Creates a leaf field selection (no sub-selections).
     */
    public static FieldSelection leaf(String fieldName) {
        return new FieldSelection(null, fieldName, List.of(), List.of());
    }

    /**
     * Creates a leaf field selection with an alias.
     */
    public static FieldSelection leaf(String alias, String fieldName) {
        return new FieldSelection(alias, fieldName, List.of(), List.of());
    }

    /**
     * Creates a field selection with sub-selections.
     */
    public static FieldSelection withSelections(String fieldName, Selection... subSelections) {
        return new FieldSelection(null, fieldName, List.of(), List.of(subSelections));
    }

    /**
     * Creates a field selection with sub-selections.
     */
    public static FieldSelection withSelections(String fieldName, List<Selection> subSelections) {
        return new FieldSelection(null, fieldName, List.of(), subSelections);
    }

    /**
     * Checks if this field has arguments.
     */
    public boolean hasArguments() {
        return !arguments.isEmpty();
    }

    /**
     * Checks if this is a leaf field (no sub-selections).
     */
    public boolean isLeaf() {
        return subSelections.isEmpty();
    }

    /**
     * Checks if this field has nested selections.
     */
    public boolean hasSubSelections() {
        return !subSelections.isEmpty();
    }

    /**
     * Returns only the field sub-selections (excludes inline fragments).
     * Useful for tests and code that only expects fields.
     */
    public List<FieldSelection> fieldSubSelections() {
        return subSelections.stream()
            .filter(s -> s instanceof FieldSelection)
            .map(s -> (FieldSelection) s)
            .toList();
    }

    @Override
    public String toString() {
        if (isLeaf()) {
            return fieldName;
        }
        return String.format("%s { %s }", fieldName,
            String.join(", ", subSelections.stream().map(Object::toString).toList()));
    }
}
