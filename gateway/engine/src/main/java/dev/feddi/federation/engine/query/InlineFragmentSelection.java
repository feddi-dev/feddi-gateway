package dev.feddi.federation.engine.query;

import graphql.language.Directive;

import java.util.List;

/**
 * Represents an inline fragment selection in a query.
 * Example: ... on Article { body wordCount }
 *
 * @param typeCondition the type condition (e.g., "Article"), or null for unconditional fragments
 * @param directives the directives on this fragment (e.g., @skip, @include)
 * @param subSelections the selections within this fragment
 */
public record InlineFragmentSelection(
    String typeCondition,
    List<Directive> directives,
    List<Selection> subSelections
) implements Selection {

    public InlineFragmentSelection {
        directives = directives == null ? List.of() : List.copyOf(directives);
        subSelections = subSelections == null ? List.of() : List.copyOf(subSelections);
    }

    /**
     * Convenience constructor without directives.
     */
    public InlineFragmentSelection(String typeCondition, List<Selection> subSelections) {
        this(typeCondition, List.of(), subSelections);
    }

    /**
     * Checks if this fragment has a type condition.
     */
    public boolean hasTypeCondition() {
        return typeCondition != null && !typeCondition.isBlank();
    }

    @Override
    public String toString() {
        String condition = hasTypeCondition() ? " on " + typeCondition : "";
        return String.format("...%s { %s }", condition,
            String.join(", ", subSelections.stream().map(Object::toString).toList()));
    }
}
