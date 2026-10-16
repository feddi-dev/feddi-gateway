package dev.feddi.federation.engine.query;

import java.util.List;

/**
 * Represents an inline fragment selection in a query.
 * Example: ... on Article { body wordCount }
 *
 * @param typeCondition the type condition (e.g., "Article"), or null for unconditional fragments
 * @param subSelections the selections within this fragment
 */
public record InlineFragmentSelection(
    String typeCondition,
    List<Selection> subSelections
) implements Selection {

    public InlineFragmentSelection {
        subSelections = subSelections == null ? List.of() : List.copyOf(subSelections);
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
