package dev.feddi.federation.engine.planner;

/**
 * Thrown when an operation cannot be planned.
 */
public class PlanningException extends RuntimeException {
    public PlanningException(String message) {
        super(message);
    }

    public PlanningException(String message, Throwable cause) {
        super(message, cause);
    }
}
