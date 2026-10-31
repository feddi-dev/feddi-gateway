package dev.feddi.federation.engine.executor;

import dev.feddi.federation.engine.IntrospectionFields;
import dev.feddi.federation.engine.parser.FieldSelectionMap.Alternative;
import dev.feddi.federation.engine.parser.FieldSelectionMap.ListSelection;
import dev.feddi.federation.engine.parser.FieldSelectionMap.ObjectField;
import dev.feddi.federation.engine.parser.FieldSelectionMap.ObjectSelection;
import dev.feddi.federation.engine.parser.FieldSelectionMap.Path;
import dev.feddi.federation.engine.parser.FieldSelectionMap.PathSegment;
import dev.feddi.federation.engine.parser.FieldSelectionMap.SelectedValue;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a step's requirements (FieldSelectionMaps) from response data: whether an object can provide them, and
 * the variable values. A type condition matches an object whose {@code __typename} is the condition's type or, for
 * an interface or union, one of its possible types ({@code possibleTypes}, from the step).
 */
final class RequirementReader {

    private final Map<String, Set<String>> possibleTypes;

    RequirementReader(Map<String, Set<String>> possibleTypes) {
        this.possibleTypes = possibleTypes;
    }

    private boolean matches(String typeCondition, String typename) {
        return typename != null && (typeCondition.equals(typename)
            || possibleTypes.getOrDefault(typeCondition, Set.of()).contains(typename));
    }

    /**
     * Extracts variables from a data context based on requirement paths.
     * All requirement variables are included in the result - if a path doesn't exist
     * in the context (e.g., a type-specific field on a different concrete type),
     * the variable is set to null.
     */
    Map<String, Object> extractVariables(Map<String, SelectedValue> requirements, Map<String, Object> context) {
        Map<String, Object> variables = new CompactMap(requirements.size());
        putVariables(requirements, context, variables);
        return variables;
    }

    /**
     * Puts the variables of {@link #extractVariables} into a map.
     */
    void putVariables(Map<String, SelectedValue> requirements, Map<String, Object> context,
                      Map<String, Object> variables) {
        for (Map.Entry<String, SelectedValue> entry : requirements.entrySet()) {
            String varName = entry.getKey();
            SelectedValue selectedValue = entry.getValue();

            Object value = extractFromSelectedValue(context, selectedValue);
            // Always include the variable, even if null - this is expected for
            // type-conditional fields (e.g., @require(field: "data.baz") when
            // the concrete type is Qux which doesn't have a baz field)
            variables.put(varName, value);
        }
    }

    /**
     * Requirements as arrays, for reading them at many positions: iterating an immutable map's entries allocates an
     * entry per requirement and position.
     */
    record Requirements(String[] names, SelectedValue[] values) {

        static Requirements of(Map<String, SelectedValue> requirements) {
            String[] names = new String[requirements.size()];
            SelectedValue[] values = new SelectedValue[requirements.size()];
            int i = 0;
            for (Map.Entry<String, SelectedValue> entry : requirements.entrySet()) {
                names[i] = entry.getKey();
                values[i++] = entry.getValue();
            }
            return new Requirements(names, values);
        }
    }

    /**
     * Puts the variables of {@link #extractVariables} into a map, for requirements as arrays.
     */
    void putVariables(Requirements requirements, Map<String, Object> context, Map<String, Object> variables) {
        String[] names = requirements.names();
        SelectedValue[] values = requirements.values();
        for (int i = 0; i < names.length; i++) {
            variables.put(names[i], extractFromSelectedValue(context, values[i]));
        }
    }

    /**
     * Extracts a value from a SelectedValue, trying alternatives in order.
     * Package-private for testing.
     */
    Object extractFromSelectedValue(Map<String, Object> context, SelectedValue selectedValue) {
        List<Alternative> alternatives = selectedValue.alternatives();
        for (int i = 0; i < alternatives.size(); i++) {
            Object value = extractFromAlternative(context, alternatives.get(i));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * Extracts a value from a single Alternative.
     */
    private Object extractFromAlternative(Map<String, Object> context, Alternative alt) {
        return switch (alt) {
            case Path path -> extractValueFromPath(context, path);
            case ObjectSelection obj -> extractFromObjectSelection(context, obj);
            case ListSelection list -> extractFromListSelection(context, list);
        };
    }

    /**
     * Extracts a value from a context following a path.
     * Handles list traversal by collecting values from all list elements.
     * Package-private for testing.
     */
    Object extractValueFromPath(Map<String, Object> context, Path path) {
        return extractValueFromPathWithIndex(context, path, 0);
    }

    /**
     * Recursive helper for extractValueFromPath that handles list traversal.
     */
    private Object extractValueFromPathWithIndex(Object current, Path path, int segmentIndex) {
        List<PathSegment> segments = path.segments();

        // Check initial type condition at the start of path extraction
        // e.g., for path <Movie>.code, only extract if context is a Movie
        if (segmentIndex == 0 && path.hasInitialTypeCondition() && current instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) current;
            String typename = (String) map.get(IntrospectionFields.TYPENAME);
            if (!matches(path.initialTypeCondition(), typename)) {
                return null;
            }
        }

        for (int i = segmentIndex; i < segments.size(); i++) {
            PathSegment segment = segments.get(i);

            if (current instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) current;

                // Get the field value first
                current = map.get(segment.fieldName());

                // Check infix type condition on the result
                if (segment.typeCondition() != null) {
                    if (current instanceof Map) {
                        // Single object: check typename directly
                        @SuppressWarnings("unchecked")
                        Map<String, Object> resultMap = (Map<String, Object>) current;
                        String typename = (String) resultMap.get(IntrospectionFields.TYPENAME);
                        if (!matches(segment.typeCondition(), typename)) {
                            return null;
                        }
                    } else if (current instanceof List) {
                        // List: filter elements by typename, then continue extraction
                        @SuppressWarnings("unchecked")
                        List<Object> list = (List<Object>) current;
                        List<Object> results = new ArrayList<>();
                        for (Object item : list) {
                            if (item instanceof Map) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> itemMap = (Map<String, Object>) item;
                                String typename = (String) itemMap.get(IntrospectionFields.TYPENAME);
                                if (matches(segment.typeCondition(), typename)) {
                                    // Type matches - continue extraction from this item
                                    Object extracted = extractValueFromPathWithIndex(item, path, i + 1);
                                    if (extracted != null) {
                                        if (extracted instanceof List) {
                                            results.addAll((List<?>) extracted);
                                        } else {
                                            results.add(extracted);
                                        }
                                    }
                                }
                            }
                        }
                        return results.isEmpty() ? null : results;
                    } else {
                        // Type condition on non-object/non-list - can't match
                        return null;
                    }
                }
            } else if (current instanceof List) {
                // When we encounter a list, extract from each element and collect results
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) current;
                List<Object> results = new ArrayList<>();
                for (Object item : list) {
                    Object extracted = extractValueFromPathWithIndex(item, path, i);
                    if (extracted != null) {
                        if (extracted instanceof List) {
                            // Flatten nested lists
                            results.addAll((List<?>) extracted);
                        } else {
                            results.add(extracted);
                        }
                    }
                }
                return results;
            } else {
                return null;
            }
        }

        return current;
    }

    /**
     * Extracts an object from an ObjectSelection pattern.
     */
    private Object extractFromObjectSelection(Map<String, Object> context, ObjectSelection obj) {
        Map<String, Object> source = context;
        if (obj.pathPrefix() != null) {
            Object prefixValue = extractValueFromPath(context, obj.pathPrefix());
            if (prefixValue instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> prefixMap = (Map<String, Object>) prefixValue;
                source = prefixMap;
            } else {
                return null;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        for (ObjectField field : obj.fields()) {
            Object value = extractFromSelectedValue(source, field.value());
            if (value != null) {
                result.put(field.name(), value);
            }
        }

        return result.isEmpty() ? null : result;
    }

    /**
     * Extracts values from a ListSelection pattern.
     */
    private Object extractFromListSelection(Map<String, Object> context, ListSelection list) {
        Object listValue;
        if (list.pathPrefix() != null) {
            listValue = extractValueFromPath(context, list.pathPrefix());
        } else {
            return null;
        }

        if (!(listValue instanceof List)) {
            return null;
        }

        @SuppressWarnings("unchecked")
        List<Object> sourceList = (List<Object>) listValue;

        List<Object> result = new ArrayList<>();
        for (Object item : sourceList) {
            if (item instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> itemMap = (Map<String, Object>) item;
                Object extracted = extractFromSelectedValue(itemMap, list.elementValue());
                if (extracted != null) {
                    result.add(extracted);
                }
            }
        }

        return result;
    }

    List<Map<String, Object>> filterContextsForRequirements(List<Map<String, Object>> contexts,
                                                                     Map<String, SelectedValue> requirements) {
        if (contexts == null || contexts.isEmpty()) {
            return List.of();
        }
        if (requirements.isEmpty()) {
            return contexts;
        }

        List<Map<String, Object>> result = new ArrayList<>(contexts.size());
        String[] sources = plainPathSources(requirements);
        for (Map<String, Object> context : contexts) {
            if (sources != null ? containsAll(context, sources) : contextCanProvideRequirements(context, requirements)) {
                result.add(context);
            }
        }
        return result;
    }

    /**
     * The first field of each requirement if every requirement is one path without type conditions (keys, mostly):
     * a context can provide them if it has these fields. Null otherwise.
     */
    private static String @Nullable [] plainPathSources(Map<String, SelectedValue> requirements) {
        String[] sources = new String[requirements.size()];
        int i = 0;
        for (SelectedValue value : requirements.values()) {
            if (value.alternatives().size() != 1 || !(value.alternatives().get(0) instanceof Path path)
                || path.hasInitialTypeCondition() || path.segments().isEmpty()) {
                return null;
            }
            sources[i++] = path.segments().get(0).fieldName();
        }
        return sources;
    }

    private static boolean containsAll(Map<String, Object> context, String[] fields) {
        for (String field : fields) {
            if (!context.containsKey(field)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks whether a context has the source fields for every requirement.
     * Null values count as present so downstream steps can still write nulls for
     * fields that cannot be resolved after a null lookup.
     */
    private boolean contextCanProvideRequirements(Map<String, Object> context,
                                                   Map<String, SelectedValue> requirements) {
        for (SelectedValue selectedValue : requirements.values()) {
            if (!contextCanProvideSelectedValue(context, selectedValue)) {
                return false;
            }
        }
        return true;
    }

    private boolean contextCanProvideSelectedValue(Map<String, Object> context, SelectedValue selectedValue) {
        List<Alternative> alternatives = selectedValue.alternatives();
        for (int i = 0; i < alternatives.size(); i++) {
            if (contextCanProvideAlternative(context, alternatives.get(i))) {
                return true;
            }
        }
        return false;
    }

    private boolean contextCanProvideAlternative(Map<String, Object> context, Alternative alt) {
        return switch (alt) {
            case Path path -> contextHasPathSource(context, path);
            case ObjectSelection obj -> contextCanProvideObjectSelection(context, obj);
            case ListSelection list -> list.pathPrefix() != null
                && contextHasPathSource(context, list.pathPrefix());
        };
    }

    private boolean contextCanProvideObjectSelection(Map<String, Object> context, ObjectSelection obj) {
        if (obj.pathPrefix() != null) {
            return contextHasPathSource(context, obj.pathPrefix());
        }
        for (ObjectField field : obj.fields()) {
            if (!contextCanProvideSelectedValue(context, field.value())) {
                return false;
            }
        }
        return true;
    }

    private boolean contextHasPathSource(Map<String, Object> context, Path path) {
        if (path.hasInitialTypeCondition()) {
            String typename = (String) context.get(IntrospectionFields.TYPENAME);
            if (!matches(path.initialTypeCondition(), typename)) {
                return false;
            }
        }
        if (path.segments().isEmpty()) {
            return true;
        }
        return context.containsKey(path.segments().get(0).fieldName());
    }
}
