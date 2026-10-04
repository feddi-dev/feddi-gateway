package dev.feddi.federation.engine.executor;

import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deep copies of subgraph result data. The executor writes into result maps when it merges
 * later steps, so a result that is handed to several consumers must give each its own copy.
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
     * Copies only the data of a result, without errors (they are reported once elsewhere).
     */
    static ExecutionResult copyData(ExecutionResult result) {
        return ExecutionResultImpl.newExecutionResult()
            .data(deepCopy(result.getData()))
            .build();
    }

    /**
     * Deep-copies maps and lists; other values are immutable and shared.
     */
    @SuppressWarnings("unchecked")
    static <T> T deepCopy(T value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>(Math.max(4, map.size() * 2));
            map.forEach((k, v) -> copy.put(k, deepCopy(v)));
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
