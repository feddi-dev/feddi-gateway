package dev.feddi.federation.engine.query;

import java.util.List;

/**
 * Represents a selection in a GraphQL query.
 * Can be either a field selection or an inline fragment.
 */
public sealed interface Selection permits FieldSelection, InlineFragmentSelection {

    /**
     * Returns the nested selections within this selection.
     */
    List<Selection> subSelections();
}
