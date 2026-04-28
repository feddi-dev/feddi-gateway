package dev.feddi.federation.engine.graph;

import dev.feddi.federation.engine.parser.FieldSelectionMap.Path;
import dev.feddi.federation.engine.parser.FieldSelectionMap.SelectedValue;
import dev.feddi.federation.engine.parser.FieldSelectionMapPrinter;
import graphql.language.Type;

import java.util.List;

/**
 * Represents a @require field dependency.
 * This is used when a field needs data from another field to be resolved.
 *
 * @param argumentName the name of the argument with the @require directive
 * @param selection the parsed FieldSelectionMap value specifying required fields
 * @param argumentType the GraphQL AST type of the argument (may be null if not available)
 * @param fieldName the name of the field that has this @require argument (may be null)
 */
public record Requirement(String argumentName, SelectedValue selection, Type<?> argumentType, String fieldName) {

    public Requirement {
        if (argumentName == null || argumentName.isBlank()) {
            throw new IllegalArgumentException("argumentName cannot be null or blank");
        }
        if (selection == null) {
            throw new IllegalArgumentException("selection cannot be null");
        }
        // argumentType and fieldName can be null
    }

    /**
     * Creates a Requirement from an argument name and parsed selection (without type).
     */
    public static Requirement of(String argumentName, SelectedValue selection) {
        return new Requirement(argumentName, selection, null, null);
    }

    /**
     * Creates a Requirement from an argument name, parsed selection, and type.
     */
    public static Requirement of(String argumentName, SelectedValue selection, Type<?> argumentType) {
        return new Requirement(argumentName, selection, argumentType, null);
    }

    /**
     * Creates a Requirement with all fields including the source field name.
     */
    public static Requirement of(String argumentName, SelectedValue selection, Type<?> argumentType, String fieldName) {
        return new Requirement(argumentName, selection, argumentType, fieldName);
    }
    
    public List<Path> extractPaths() {
        return selection.extractPaths();
    }

    @Override
    public String toString() {
        return String.format("@require(field: \"%s\") on argument '%s'", 
            FieldSelectionMapPrinter.print(selection), argumentName);
    }
}
