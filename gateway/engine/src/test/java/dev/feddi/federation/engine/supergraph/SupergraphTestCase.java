package dev.feddi.federation.engine.supergraph;

import java.util.Map;

/**
 * Represents a supergraph composition test case.
 * 
 * Test cases can be either:
 * - Positive: Has a supergraph field (composition should succeed)
 * - Negative: Has an error field (composition should fail with the expected error code)
 *
 * @param name test case name
 * @param description optional description
 * @param subgraphs map of subgraph name to SDL
 * @param supergraph expected supergraph SDL (null for error cases)
 * @param error expected error code from spec (null for success cases)
 * @param sourcePath source file path for error messages
 */
public record SupergraphTestCase(
    String name,
    String description,
    Map<String, String> subgraphs,
    String supergraph,
    String error,
    String sourcePath
) {
    public SupergraphTestCase {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name cannot be null or blank");
        }
        if (subgraphs == null || subgraphs.isEmpty()) {
            throw new IllegalArgumentException("subgraphs cannot be null or empty");
        }
        // Either supergraph or error must be present, but not both
        if (supergraph == null && error == null) {
            throw new IllegalArgumentException("Either supergraph or error must be specified");
        }
        if (supergraph != null && !supergraph.isBlank() && error != null && !error.isBlank()) {
            throw new IllegalArgumentException("Cannot specify both supergraph and error");
        }
    }
    
    /**
     * Returns true if this is a positive test (expects successful composition).
     */
    public boolean expectsSuccess() {
        return supergraph != null && !supergraph.isBlank();
    }
    
    /**
     * Returns true if this is a negative test (expects validation error).
     */
    public boolean expectsError() {
        return error != null && !error.isBlank();
    }
}
