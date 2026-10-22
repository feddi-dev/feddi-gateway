package dev.feddi.federation.engine.executor;

import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deep copies of subgraph result data. The executor writes into result maps when it merges
 * later steps, so a result that is handed to consumers on different response paths must give
 * each its own copy.
 */
final class ResultCopies {

    private ResultCopies() {
    }

    /**
     * Copies the data of a result; errors and extensions are shared (they are not modified).
     */
    static ExecutionResult copy(ExecutionResult result) {
        return ExecutionResultImpl.newExecutionResult()
            .data(deepCopy(result.getData()))
            .errors(result.getErrors())
            .extensions(result.getExtensions())
            .build();
    }

    /**
     * The data of a lookup result for positions of another step: a deep copy, or the data itself when merging never
     * writes into it. A merge copies the fields of the lookup's object into the position and links only the objects
     * and lists among them into the response, where later steps write into them: a lookup object with nothing but
     * leaf values (e.g. {@code {product: {name: "Table", price: 899}}}) stays as it is and can be shared.
     */
    static <T> T forMerge(T data) {
        if (data instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                if (value instanceof List<?> || value instanceof Map<?, ?> object && !leavesOnly(object)) {
                    return deepCopy(data);
                }
            }
            return data;
        }
        return deepCopy(data);
    }

    private static boolean leavesOnly(Map<?, ?> object) {
        for (Object value : object.values()) {
            if (value instanceof Map<?, ?> || value instanceof List<?>) {
                return false;
            }
        }
        return true;
    }

    /**
     * Deep-copies maps and lists; other values are immutable and shared.
     */
    @SuppressWarnings("unchecked")
    static <T> T deepCopy(T value) {
        if (value instanceof Map<?, ?> map) {
            CompactMap copy = new CompactMap(map.size());
            map.forEach((k, v) -> copy.append((String) k, deepCopy(v)));
            return (T) copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(deepCopy(item));
            }
            return (T) copy;
        }
        return value;
    }
}
