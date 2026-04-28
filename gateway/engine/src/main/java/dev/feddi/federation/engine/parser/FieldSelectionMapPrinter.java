package dev.feddi.federation.engine.parser;

import dev.feddi.federation.engine.parser.FieldSelectionMap.Alternative;
import dev.feddi.federation.engine.parser.FieldSelectionMap.ListSelection;
import dev.feddi.federation.engine.parser.FieldSelectionMap.ObjectField;
import dev.feddi.federation.engine.parser.FieldSelectionMap.ObjectSelection;
import dev.feddi.federation.engine.parser.FieldSelectionMap.Path;
import dev.feddi.federation.engine.parser.FieldSelectionMap.PathSegment;
import dev.feddi.federation.engine.parser.FieldSelectionMap.SelectedValue;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Prints a FieldSelectionMap AST back to its string representation.
 */
public final class FieldSelectionMapPrinter {

    /**
     * Converts a SelectedValue to its string representation.
     *
     * @param selectedValue the value to print
     * @return the string representation
     */
    public static String print(SelectedValue selectedValue) {
        if (selectedValue == null || selectedValue.alternatives() == null || selectedValue.alternatives().isEmpty()) {
            return "";
        }
        return selectedValue.alternatives().stream()
            .map(FieldSelectionMapPrinter::printAlternative)
            .collect(Collectors.joining(" | "));
    }

    private static String printAlternative(Alternative alternative) {
        return switch (alternative) {
            case Path path -> printPath(path);
            case ObjectSelection objectSelection -> printObjectSelection(objectSelection);
            case ListSelection listSelection -> printListSelection(listSelection);
        };
    }

    private static String printPath(Path path) {
        if (path.segments() == null || path.segments().isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        // Print initial type condition if present: <Type>.
        if (path.hasInitialTypeCondition()) {
            sb.append("<").append(path.initialTypeCondition()).append(">.");
        }

        List<PathSegment> segments = path.segments();

        for (int i = 0; i < segments.size(); i++) {
            PathSegment segment = segments.get(i);

            if (i > 0) {
                sb.append(".");
            }

            // Print field name
            sb.append(segment.fieldName());

            // Print infix type condition if present: field<Type>
            if (segment.hasTypeCondition()) {
                sb.append("<").append(segment.typeCondition()).append(">");
            }
        }

        return sb.toString();
    }

    private static String printObjectSelection(ObjectSelection objectSelection) {
        StringBuilder sb = new StringBuilder();
        
        if (objectSelection.pathPrefix() != null) {
            sb.append(printPath(objectSelection.pathPrefix()));
            sb.append(".");
        }
        
        sb.append("{ ");
        sb.append(objectSelection.fields().stream()
            .map(FieldSelectionMapPrinter::printObjectField)
            .collect(Collectors.joining(" ")));
        sb.append(" }");
        
        return sb.toString();
    }

    private static String printObjectField(ObjectField field) {
        // Check if it's a shorthand (field name equals the path)
        if (isShorthand(field)) {
            return field.name();
        }
        return field.name() + ": " + print(field.value());
    }

    private static boolean isShorthand(ObjectField field) {
        if (field.value() == null || field.value().alternatives() == null) {
            return false;
        }
        List<Alternative> alternatives = field.value().alternatives();
        if (alternatives.size() != 1) {
            return false;
        }
        Alternative alt = alternatives.get(0);
        if (alt instanceof Path path) {
            // Shorthand requires: no initial type condition, single segment, no infix type condition
            if (!path.hasInitialTypeCondition() && path.segments().size() == 1) {
                PathSegment segment = path.segments().get(0);
                return segment.fieldName().equals(field.name()) && !segment.hasTypeCondition();
            }
        }
        return false;
    }

    private static String printListSelection(ListSelection listSelection) {
        StringBuilder sb = new StringBuilder();
        
        if (listSelection.pathPrefix() != null) {
            sb.append(printPath(listSelection.pathPrefix()));
        }
        
        sb.append("[");
        sb.append(print(listSelection.elementValue()));
        sb.append("]");
        
        return sb.toString();
    }
}
