package dev.feddi.federation.engine.testcase;

import dev.feddi.federation.engine.planner.ExecutionPlan;
import dev.feddi.federation.engine.query.Operation;

/**
 * Represents a single query test case.
 *
 * @param name the name of the test
 * @param description optional description
 * @param operation the operation to plan
 * @param expectedPlan the expected execution plan (optional)
 */
public record QueryTest(
    String name,
    String description,
    Operation operation,
    ExecutionPlan expectedPlan
) {
    
    public QueryTest {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name cannot be null or blank");
        }
        if (operation == null) {
            throw new IllegalArgumentException("operation cannot be null");
        }
    }
    
    /**
     * Checks if this test has an expected plan for verification.
     */
    public boolean hasExpectedPlan() {
        return expectedPlan != null;
    }
    
    @Override
    public String toString() {
        return String.format("QueryTest[%s]", name);
    }
}
