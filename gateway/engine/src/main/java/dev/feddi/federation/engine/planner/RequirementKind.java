package dev.feddi.federation.engine.planner;

/**
 * Distinguishes how an execution-step requirement was introduced.
 *
 * <ul>
 *   <li>{@link #KEY} — from a {@code @lookup} argument {@code @is} mapping; null keys
 *       mean the entity cannot be resolved and the subgraph call is skipped.</li>
 *   <li>{@link #REQUIRE} — from a field argument {@code @require}; null handling
 *       depends on argument (and later field-return) nullability.</li>
 * </ul>
 */
public enum RequirementKind {
    KEY,
    REQUIRE
}
