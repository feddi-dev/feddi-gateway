package dev.feddi.federation.engine;

import dev.feddi.federation.engine.query.FieldSelection;
import dev.feddi.federation.engine.query.InlineFragmentSelection;
import dev.feddi.federation.engine.query.Operation;
import dev.feddi.federation.engine.query.Selection;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates that GraphQL responses only contain fields that were requested in the query.
 * This enforces the fundamental GraphQL principle: you only get back what you ask for.
 */
public final class ResponseFieldValidator {

    private ResponseFieldValidator() {
        // Utility class
    }

    /**
     * Validates that the response contains only fields that were requested in the query.
     *
     * @param query the parsed query
     * @param response the response data (the value of "data" in the GraphQL response)
     * @return list of validation errors, empty if valid
     */
    public static List<String> validate(Operation query, Map<String, Object> response) {
        if (response == null) {
            return List.of();
        }
        return validateSelections(query.selections(), response, "");
    }

    /**
     * Validates that the response contains only fields that were requested in the operation.
     *
     * @param operationString the GraphQL operation string
     * @param response the response data (the value of "data" in the GraphQL response)
     * @return list of validation errors, empty if valid
     */
    public static List<String> validate(String operationString, Map<String, Object> response) {
        if (response == null) {
            return List.of();
        }
        Operation query = Operation.parse(operationString);
        return validate(query, response);
    }

    /**
     * Validates selections against a response map.
     *
     * @param selections the expected selections
     * @param response the response map to validate
     * @param path current path for error messages
     * @return list of validation errors
     */
    private static List<String> validateSelections(List<Selection> selections,
                                                    Map<String, Object> response,
                                                    String path) {
        List<String> errors = new ArrayList<>();

        // Extract all allowed field names from selections (including from inline fragments)
        Set<String> allowedFields = extractAllowedFields(selections);

        // Check each key in response
        for (String key : response.keySet()) {
            // __typename is always allowed (introspection field)
            if ("__typename".equals(key)) {
                continue;
            }

            if (!allowedFields.contains(key)) {
                String fullPath = path.isEmpty() ? key : path + "." + key;
                errors.add("Unexpected field in response: " + fullPath);
            }
        }

        // Recursively validate nested objects
        for (Selection sel : selections) {
            errors.addAll(validateNestedSelection(sel, response, path));
        }

        return errors;
    }

    /**
     * Validates a single selection and its nested content.
     */
    private static List<String> validateNestedSelection(Selection selection,
                                                         Map<String, Object> response,
                                                         String path) {
        List<String> errors = new ArrayList<>();

        if (selection instanceof FieldSelection fieldSelection) {
            if (fieldSelection.hasSubSelections()) {
                // Use responseKey (alias if present, otherwise field name) for response lookup
                String responseKey = fieldSelection.responseKey();
                Object value = response.get(responseKey);
                String fieldPath = path.isEmpty() ? responseKey : path + "." + responseKey;

                if (value instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> nestedMap = (Map<String, Object>) value;
                    errors.addAll(validateSelections(fieldSelection.subSelections(), nestedMap, fieldPath));
                } else if (value instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Object> list = (List<Object>) value;
                    for (int i = 0; i < list.size(); i++) {
                        Object item = list.get(i);
                        if (item instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> itemMap = (Map<String, Object>) item;
                            String itemPath = fieldPath + "[" + i + "]";
                            errors.addAll(validateSelections(fieldSelection.subSelections(), itemMap, itemPath));
                        }
                    }
                }
                // null values or scalars: nothing to validate
            }
        } else if (selection instanceof InlineFragmentSelection inlineFragment) {
            // For inline fragments, validate nested fields against the same response
            // (the fields are at the same level in the response)
            for (Selection sub : inlineFragment.subSelections()) {
                errors.addAll(validateNestedSelection(sub, response, path));
            }
        }

        return errors;
    }

    /**
     * Extracts all allowed response keys from a list of selections.
     * This includes fields from inline fragments (which are at the same level).
     * Uses responseKey() which returns the alias if present, otherwise the field name.
     */
    private static Set<String> extractAllowedFields(List<Selection> selections) {
        Set<String> allowed = new HashSet<>();

        for (Selection sel : selections) {
            if (sel instanceof FieldSelection fieldSelection) {
                allowed.add(fieldSelection.responseKey());
            } else if (sel instanceof InlineFragmentSelection inlineFragment) {
                // Fields inside inline fragments are at the same level in the response
                allowed.addAll(extractAllowedFields(inlineFragment.subSelections()));
            }
        }

        return allowed;
    }
}
