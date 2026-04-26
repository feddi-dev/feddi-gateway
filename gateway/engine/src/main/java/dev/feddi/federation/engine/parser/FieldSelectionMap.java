package dev.feddi.federation.engine.parser;

import java.util.ArrayList;
import java.util.List;

/**
 * Model classes for the FieldSelectionMap and FieldSelectionSet scalar types.
 *
 * FieldSelectionMap (SelectedValue) is used by @is and @require directives.
 * FieldSelectionSet is used by @key and @provides directives.
 */
public final class FieldSelectionMap {

    // ========================================================================
    // FieldSelectionSet - used by @key and @provides
    // Supports GraphQL-like selection set syntax: "id name", "author { id name }"
    // ========================================================================

    /**
     * A FieldSelectionSet containing a list of selection items.
     * Used by @key and @provides directives.
     */
    public record FieldSelectionSet(List<SelectionItem> items) {
        public FieldSelectionSet(SelectionItem... items) {
            this(List.of(items));
        }
    }

    /**
     * A selection item - either a field selection or an inline fragment.
     */
    public sealed interface SelectionItem permits FieldSelection, InlineFragment {}

    /**
     * A field selection, optionally with nested sub-selections.
     */
    public record FieldSelection(String fieldName, List<SelectionItem> subSelections) implements SelectionItem {
        public FieldSelection(String fieldName) {
            this(fieldName, List.of());
        }

        public boolean hasSubSelections() {
            return subSelections != null && !subSelections.isEmpty();
        }
    }

    /**
     * An inline fragment with a type condition: ... on TypeName { selections }
     */
    public record InlineFragment(String typeName, List<SelectionItem> selections) implements SelectionItem {
        public InlineFragment(String typeName, SelectionItem... selections) {
            this(typeName, List.of(selections));
        }
    }

    // ========================================================================
    // FieldSelectionMap (SelectedValue) - used by @is and @require
    // Supports paths, type conditions, and pipe-separated alternatives
    // ========================================================================

    public record SelectedValue(List<Alternative> alternatives) {
        public SelectedValue(Alternative alternative) {
            this(List.of(alternative));
        }

        public SelectedValue(Alternative... alternatives) {
            this(List.of(alternatives));
        }

        public static SelectedValue empty() {
            return new SelectedValue(List.of());
        }

        /**
         * Extracts all field paths from this SelectedValue, flattening object selections
         * and list selections into simple Path objects.
         *
         * For simple paths like "id" or "user.id", returns a single Path.
         * For object selections like "{ sku category brand }", returns a Path for each field.
         * For list selections like "items[productId]", returns the element paths with prefix.
         *
         * @return list of paths representing fields that need to be fetched
         */
        public List<Path> extractPaths() {
            List<Path> paths = new ArrayList<>();
            for (Alternative alt : alternatives) {
                collectPathsFromAlternative(alt, null, paths);
            }
            return paths;
        }

        private static void collectPathsFromAlternative(Alternative alt, Path prefix, List<Path> paths) {
            switch (alt) {
                case Path path -> {
                    paths.add(prependPath(prefix, path));
                }
                case ObjectSelection obj -> {
                    Path objPrefix = prefix;
                    if (obj.pathPrefix() != null) {
                        objPrefix = prependPath(prefix, obj.pathPrefix());
                    }
                    for (ObjectField field : obj.fields()) {
                        collectPathsFromSelectedValue(field.value(), objPrefix, paths);
                    }
                }
                case ListSelection list -> {
                    Path listPrefix = prefix;
                    if (list.pathPrefix() != null) {
                        listPrefix = prependPath(prefix, list.pathPrefix());
                    }
                    collectPathsFromSelectedValue(list.elementValue(), listPrefix, paths);
                }
            }
        }

        private static void collectPathsFromSelectedValue(SelectedValue value, Path prefix, List<Path> paths) {
            for (Alternative alt : value.alternatives()) {
                collectPathsFromAlternative(alt, prefix, paths);
            }
        }

        private static Path prependPath(Path prefix, Path path) {
            if (prefix == null) {
                return path;
            }
            List<PathSegment> combined = new ArrayList<>(prefix.segments());
            if (path.hasInitialTypeCondition() && !path.segments().isEmpty()) {
                PathSegment firstInner = path.segments().get(0);
                combined.add(new PathSegment(firstInner.fieldName(), path.initialTypeCondition()));
                for (int i = 1; i < path.segments().size(); i++) {
                    combined.add(path.segments().get(i));
                }
                return new Path(prefix.initialTypeCondition(), combined);
            } else {
                combined.addAll(path.segments());
                String initialTypeCondition = path.hasInitialTypeCondition()
                    ? path.initialTypeCondition()
                    : prefix.initialTypeCondition();
                return new Path(initialTypeCondition, combined);
            }
        }
    }

    public sealed interface Alternative permits Path, ListSelection, ObjectSelection {
    }

    /**
     * A path selecting fields from the output type.
     *
     * The spec grammar distinguishes two type condition positions:
     * - Initial: {@code <Type>.field} - specifies the lookup context for the first field
     * - Infix: {@code field<Type>.next} - specifies the return type of a field (type narrowing)
     *
     * @param initialTypeCondition the type condition before the first field (e.g., "Movie" in {@code <Movie>.imdbCode}),
     *                             or null if not present
     * @param segments the path segments, each with a field name and optional infix type condition
     */
    public record Path(String initialTypeCondition, List<PathSegment> segments) implements Alternative {
        public Path(PathSegment... segments) {
            this(null, List.of(segments));
        }

        public Path(List<PathSegment> segments) {
            this(null, segments);
        }

        public Path(String initialTypeCondition, PathSegment... segments) {
            this(initialTypeCondition, List.of(segments));
        }

        public static Path of(String singleSegmentName) {
            return new Path(null, List.of(new PathSegment(singleSegmentName)));
        }

        /**
         * Returns true if this path has an initial type condition (e.g., {@code <Movie>.imdbCode}).
         */
        public boolean hasInitialTypeCondition() {
            return initialTypeCondition != null;
        }
    }

    /**
     * A segment in a path, representing a field selection with optional type narrowing.
     *
     * @param fieldName the name of the field being selected
     * @param typeCondition the infix type condition after this field (e.g., "Book" in {@code mediaById<Book>.isbn}),
     *                      specifying the return type for type narrowing, or null if not present
     */
    public record PathSegment(String fieldName, String typeCondition) {
        public PathSegment(String fieldName) {
            this(fieldName, null);
        }

        /**
         * Returns true if this segment has an infix type condition.
         */
        public boolean hasTypeCondition() {
            return typeCondition != null;
        }
    }

    public record ObjectSelection(
        Path pathPrefix,
        List<ObjectField> fields
    ) implements Alternative {
        public ObjectSelection(Path pathPrefix, ObjectField... fields) {
            this(pathPrefix, List.of(fields));
        }

        public ObjectSelection(ObjectField... fields) {
            this(null, List.of(fields));
        }
    }

    public record ListSelection(
        Path pathPrefix,
        SelectedValue elementValue
    ) implements Alternative {
        ListSelection(SelectedValue selectedValue) {
            this(null, selectedValue);
        }
    }

    public record ObjectField(String name, SelectedValue value) {
    }
}
