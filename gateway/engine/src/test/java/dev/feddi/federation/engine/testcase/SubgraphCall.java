package dev.feddi.federation.engine.testcase;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Represents an expected subgraph call in an execution test.
 *
 * @param id optional identifier for ordering control (null if not specified)
 * @param subgraph the target subgraph name
 * @param operation the expected GraphQL operation string
 * @param variables the expected variables for this call
 * @param response the mock response to return (can contain both "data" and "errors")
 * @param delayMs optional delay in milliseconds before returning response (null = no delay,
 *                Long.MAX_VALUE = "infinite" delay for timeout testing)
 * @param failWithError optional error message to simulate a subgraph call failure (network error, etc.)
 *                      When set, the call will fail with this error instead of returning the response.
 */
public record SubgraphCall(
    String id,
    String subgraph,
    String operation,
    Map<String, Object> variables,
    Map<String, Object> response,
    Long delayMs,
    String failWithError
) {
    public SubgraphCall {
        // id is optional, can be null
        if (subgraph == null || subgraph.isBlank()) {
            throw new IllegalArgumentException("subgraph cannot be null or blank");
        }
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("operation cannot be null or blank");
        }
        variables = variables == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
        response = response == null ? Map.of() : response;
        // delayMs is optional, can be null
    }

    /**
     * Creates a SubgraphCall without an explicit id, delay, or failure.
     */
    public static SubgraphCall of(String subgraph, String operation,
                                   Map<String, Object> variables, Map<String, Object> response) {
        return new SubgraphCall(null, subgraph, operation, variables, response, null, null);
    }

    /**
     * Returns true if this call should simulate a failure.
     */
    public boolean shouldFail() {
        return failWithError != null && !failWithError.isBlank();
    }
}
