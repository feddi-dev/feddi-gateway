package dev.feddi.federation.engine.testcase;

import java.util.List;
import java.util.Map;

/**
 * Represents an execution test case loaded from YAML.
 *
 * @param name the test name
 * @param description optional test description
 * @param query the GraphQL query to execute
 * @param variables query variables
 * @param subgraphCalls expected subgraph calls with mock responses
 * @param finishOrder optional ordering for when subgraph calls should complete
 *                    (e.g., "1,3,2" means call 1 finishes, then 3, then 2)
 *                    For repeated executions, use indices: "1,2[1],2[0]" means
 *                    call 2's second execution finishes before its first
 * @param timeoutMs optional custom timeout in milliseconds for this test
 *                  (null = use default, typically 30000ms)
 * @param expectedResponse the expected final merged response
 * @param skip if true, skip this test (useful for documenting not-yet-implemented features)
 */
public record ExecutionTest(
    String name,
    String description,
    String query,
    Map<String, Object> variables,
    List<SubgraphCall> subgraphCalls,
    String finishOrder,
    Long timeoutMs,
    Map<String, Object> expectedResponse,
    Boolean skip
) {
    public ExecutionTest {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name cannot be null or blank");
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query cannot be null or blank");
        }
        variables = variables == null ? Map.of() : Map.copyOf(variables);
        subgraphCalls = subgraphCalls == null ? List.of() : List.copyOf(subgraphCalls);
        // finishOrder is optional, can be null
        // timeoutMs is optional, can be null
        expectedResponse = expectedResponse == null ? Map.of() : expectedResponse;
        // skip is optional, defaults to false
        skip = skip != null && skip;
    }

    /**
     * Checks if this test should be skipped.
     */
    public boolean shouldSkip() {
        return skip != null && skip;
    }

    /**
     * Creates an ExecutionTest without a finishOrder or custom timeout.
     */
    public static ExecutionTest of(String name, String description, String query,
                                   Map<String, Object> variables,
                                   List<SubgraphCall> subgraphCalls,
                                   Map<String, Object> expectedResponse) {
        return new ExecutionTest(name, description, query, variables, subgraphCalls, null, null, expectedResponse, false);
    }
}
