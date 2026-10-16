package dev.feddi.federation.engine.compose.validation;

import dev.feddi.federation.engine.parser.FieldSelectionMap.Alternative;
import dev.feddi.federation.engine.parser.FieldSelectionMap.ListSelection;
import dev.feddi.federation.engine.parser.FieldSelectionMap.ObjectField;
import dev.feddi.federation.engine.parser.FieldSelectionMap.ObjectSelection;
import dev.feddi.federation.engine.parser.FieldSelectionMap.Path;
import dev.feddi.federation.engine.parser.FieldSelectionMap.PathSegment;
import dev.feddi.federation.engine.parser.FieldSelectionMap.SelectedValue;

/**
 * Walks a FieldSelectionMap ({@code @is}, {@code @require}) through the types of source schemas, from the type it
 * is rooted at: every alternative, path segment, type condition, object field and list element, reporting each
 * selected field and type condition with the type in scope (Appendix A). A path whose field or type condition the
 * visitor rejects is not followed further; its type for an object or list selection is still known.
 */
public final class SelectionMapWalker {

    /** What a walk reports. */
    public interface Visitor {
        /**
         * A field selected on {@code parentType}.
         *
         * @return false to stop following this path
         */
        boolean field(String parentType, PathSegment segment);

        /**
         * A type condition narrowing {@code scopeType} (the type in scope, or the return type of the field it
         * follows; null if unknown) to {@code condition}. {@code inputPath} locates it in the argument's value: the object field
         * names on the way, and for a condition after a field, that field's name.
         *
         * @return false to stop following this path
         */
        default boolean typeCondition(String scopeType, String condition, String inputPath) {
            return true;
        }
    }

    private final SourceSchemas types;

    /** @param types the schemas whose fields give the type after each field */
    public SelectionMapWalker(SourceSchemas types) {
        this.types = types;
    }

    /** Walks {@code value} from {@code rootType}. */
    public void walk(SelectedValue value, String rootType, Visitor visitor) {
        walk(value, rootType, "", visitor);
    }

    private void walk(SelectedValue value, String typeName, String inputPath, Visitor visitor) {
        for (Alternative alternative : value.alternatives()) {
            switch (alternative) {
                case Path path -> walkPath(path, typeName, inputPath, visitor);
                case ObjectSelection object -> {
                    String objectType = object.pathPrefix() == null ? typeName
                        : walkPath(object.pathPrefix(), typeName, inputPath, visitor);
                    for (ObjectField field : object.fields()) {
                        if (field.value() != null) {
                            walk(field.value(), objectType, inputPath.isEmpty() ? field.name()
                                : inputPath + "." + field.name(), visitor);
                        }
                    }
                }
                case ListSelection list -> {
                    String elementType = list.pathPrefix() == null ? typeName
                        : walkPath(list.pathPrefix(), typeName, inputPath, visitor);
                    if (list.elementValue() != null) {
                        walk(list.elementValue(), elementType, inputPath, visitor);
                    }
                }
            }
        }
    }

    /** Reports a path's fields and type conditions while the visitor accepts them; returns the type it leads to. */
    private String walkPath(Path path, String typeName, String inputPath, Visitor visitor) {
        String type = typeName;
        boolean following = true;
        if (path.hasInitialTypeCondition()) {
            following = visitor.typeCondition(type, path.initialTypeCondition(), inputPath);
            type = path.initialTypeCondition();
        }
        for (PathSegment segment : path.segments()) {
            following = following && visitor.field(type, segment);
            String fieldType = types.fieldType(type, segment.fieldName());
            if (segment.hasTypeCondition()) {
                following = following && visitor.typeCondition(fieldType, segment.typeCondition(),
                    inputPath.isEmpty() ? segment.fieldName() : inputPath + "." + segment.fieldName());
                type = segment.typeCondition();
            } else if (fieldType != null) {
                type = fieldType;
            }
        }
        return type;
    }
}
