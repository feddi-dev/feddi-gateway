package dev.feddi.federation.engine.planner;

/**
 * Origin of a field in a subgraph operation: whether the client requested it or the planner added it for a lookup
 * or a @require dependency (then it is removed from the response unless the client requested it too).
 */
enum FieldOrigin {
    /** Field was explicitly requested by the client query */
    REQUESTED,
    /** Field was added for @key/@is lookup requirements */
    ARTIFICIAL_KEY,
    /** Field was added for @require directive dependencies */
    ARTIFICIAL_REQUIRE
}
