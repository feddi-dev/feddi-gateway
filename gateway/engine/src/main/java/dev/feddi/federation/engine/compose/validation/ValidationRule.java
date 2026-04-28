package dev.feddi.federation.engine.compose.validation;

import dev.feddi.federation.engine.compose.Subgraph;

import java.util.List;

/**
 * Interface for validation rules.
 */
public interface ValidationRule {
    
    /**
     * Gets the validation phase this rule applies to.
     */
    ValidationPhase phase();
    
    /**
     * Gets the rule name/identifier.
     */
    String name();
    
    /**
     * Validates the subgraphs and returns any diagnostics.
     *
     * @param subgraphs the list of subgraphs to validate
     * @return validation result with any diagnostics
     */
    ValidationResult validate(List<Subgraph> subgraphs);
}
