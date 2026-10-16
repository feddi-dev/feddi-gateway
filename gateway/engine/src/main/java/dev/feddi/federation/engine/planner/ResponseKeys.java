package dev.feddi.federation.engine.planner;

import dev.feddi.federation.engine.graph.LookupArgument;
import dev.feddi.federation.engine.parser.FieldSelectionMap.Alternative;
import dev.feddi.federation.engine.parser.FieldSelectionMap.ListSelection;
import dev.feddi.federation.engine.parser.FieldSelectionMap.ObjectField;
import dev.feddi.federation.engine.parser.FieldSelectionMap.ObjectSelection;
import dev.feddi.federation.engine.parser.FieldSelectionMap.Path;
import dev.feddi.federation.engine.parser.FieldSelectionMap.PathSegment;
import dev.feddi.federation.engine.parser.FieldSelectionMap.SelectedValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rewrites FieldSelectionMap paths to the response keys under which a plan selects their fields (a field the planner
 * adds can be aliased, e.g. {@code __gql_code}).
 */
final class ResponseKeys {

    private ResponseKeys() {
    }

    /** Identifies a path segment's field in response key maps: its name, and its arguments if any. */
    static String segmentKey(PathSegment segment) {
        return segment.hasArguments() ? segment.fieldName() + "(" + segment.printedArguments() + ")"
            : segment.fieldName();
    }

    /**
     * Creates a Path with response keys substituted for field names.
     * Used for building requirement paths where field names may be aliased.
     *
     * @param path the original path
     * @param fieldToResponseKey map from field names to their response keys
     * @return a Path with response keys substituted for field names
     */
    static Path createNestedPath(Path path, Map<String, String> fieldToResponseKey) {
        List<PathSegment> pathSegments = new ArrayList<>();
        for (PathSegment segment : path.segments()) {
            String responseKey = fieldToResponseKey.getOrDefault(segmentKey(segment), segment.fieldName());
            pathSegments.add(new PathSegment(responseKey));
        }
        return new Path(path.initialTypeCondition(), pathSegments);
    }

    /**
     * Creates a SelectedValue for a requirement from a LookupArgument, applying response key mapping.
     * For single-path arguments, returns a single-path SelectedValue.
     * For multi-alternative arguments (e.g., {@code <Book>.isbn | <Electronics>.sku}),
     * returns a SelectedValue with all alternatives preserved.
     */
    static SelectedValue createRequirementSelectedValue(LookupArgument lookupArg, Map<String, String> nestedResponseKeys) {
        List<Path> paths = lookupArg.extractPaths();
        if (paths.size() == 1) {
            return new SelectedValue(createNestedPath(paths.get(0), nestedResponseKeys));
        }
        List<Alternative> alternatives = new ArrayList<>();
        for (Path path : paths) {
            alternatives.add(createNestedPath(path, nestedResponseKeys));
        }
        return new SelectedValue(alternatives);
    }

    /**
     * Transforms a SelectedValue by replacing field names with their response keys.
     * This handles aliased fields where the response key differs from the field name.
     */
    static SelectedValue transformSelectionWithResponseKeys(SelectedValue selection,
                                                              Map<String, String> responseKeys) {
        if (responseKeys == null || responseKeys.isEmpty()) {
            return selection;
        }

        List<Alternative> transformedAlts = new ArrayList<>();
        for (Alternative alt : selection.alternatives()) {
            transformedAlts.add(transformAlternativeWithResponseKeys(alt, responseKeys));
        }
        return new SelectedValue(transformedAlts);
    }

    private static Alternative transformAlternativeWithResponseKeys(Alternative alt,
                                                               Map<String, String> responseKeys) {
        return switch (alt) {
            case Path path -> transformPathWithResponseKeys(path, responseKeys);
            case ObjectSelection obj -> transformObjectSelectionWithResponseKeys(obj, responseKeys);
            case ListSelection list ->
                transformListSelectionWithResponseKeys(list, responseKeys);
        };
    }

    private static Path transformPathWithResponseKeys(Path path, Map<String, String> responseKeys) {
        List<PathSegment> transformedSegments = new ArrayList<>();
        for (var segment : path.segments()) {
            String responseKey = responseKeys.getOrDefault(segmentKey(segment), segment.fieldName());
            transformedSegments.add(new PathSegment(
                responseKey, segment.typeCondition()));
        }
        // Preserve the initial type condition
        return new Path(path.initialTypeCondition(), transformedSegments);
    }

    private static ObjectSelection transformObjectSelectionWithResponseKeys(ObjectSelection obj,
                                                                       Map<String, String> responseKeys) {
        Path transformedPrefix = obj.pathPrefix() != null
            ? transformPathWithResponseKeys(obj.pathPrefix(), responseKeys)
            : null;

        List<ObjectField> transformedFields = new ArrayList<>();
        for (var field : obj.fields()) {
            SelectedValue transformedValue = transformSelectionWithResponseKeys(field.value(), responseKeys);
            transformedFields.add(new ObjectField(
                field.name(), transformedValue));
        }
        return new ObjectSelection(transformedPrefix, transformedFields);
    }

    private static ListSelection transformListSelectionWithResponseKeys(
            ListSelection list,
            Map<String, String> responseKeys) {
        Path transformedPrefix = list.pathPrefix() != null
            ? transformPathWithResponseKeys(list.pathPrefix(), responseKeys)
            : null;

        SelectedValue transformedElement = transformSelectionWithResponseKeys(list.elementValue(), responseKeys);
        return new ListSelection(
            transformedPrefix, transformedElement);
    }
}
