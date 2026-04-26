package dev.feddi.federation.engine.compose.validation;

/**
 * Validation phases as defined in the composition spec.
 */
public enum ValidationPhase {
    /**
     * Validates individual source schemas before any merging.
     */
    SOURCE_SCHEMA,
    
    /**
     * Validates cross-schema relationships before merging.
     */
    PRE_MERGE,
    
    /**
     * Validates the merged schema after composition.
     */
    POST_MERGE
}
