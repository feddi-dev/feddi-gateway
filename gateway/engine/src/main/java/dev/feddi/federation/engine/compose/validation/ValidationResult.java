package dev.feddi.federation.engine.compose.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result of schema validation containing diagnostics.
 */
public final class ValidationResult {
    
    private final List<Diagnostic> diagnostics;
    
    private ValidationResult(List<Diagnostic> diagnostics) {
        this.diagnostics = Collections.unmodifiableList(new ArrayList<>(diagnostics));
    }
    
    /**
     * Creates an empty (successful) validation result.
     */
    public static ValidationResult success() {
        return new ValidationResult(List.of());
    }
    
    /**
     * Creates a validation result with diagnostics.
     */
    public static ValidationResult of(List<Diagnostic> diagnostics) {
        return new ValidationResult(diagnostics);
    }
    
    /**
     * Creates a validation result with a single diagnostic.
     */
    public static ValidationResult of(Diagnostic diagnostic) {
        return new ValidationResult(List.of(diagnostic));
    }
    
    /**
     * Gets all diagnostics.
     */
    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }
    
    /**
     * Gets only error diagnostics.
     */
    public List<Diagnostic> errors() {
        return diagnostics.stream()
            .filter(Diagnostic::isError)
            .toList();
    }
    
    /**
     * Gets only warning diagnostics.
     */
    public List<Diagnostic> warnings() {
        return diagnostics.stream()
            .filter(Diagnostic::isWarning)
            .toList();
    }
    
    /**
     * Checks if validation passed (no errors).
     */
    public boolean isValid() {
        return errors().isEmpty();
    }
    
    /**
     * Checks if there are any errors.
     */
    public boolean hasErrors() {
        return !errors().isEmpty();
    }
    
    /**
     * Checks if there are any warnings.
     */
    public boolean hasWarnings() {
        return !warnings().isEmpty();
    }
    
    /**
     * Merges this result with another.
     */
    public ValidationResult merge(ValidationResult other) {
        List<Diagnostic> merged = new ArrayList<>(this.diagnostics);
        merged.addAll(other.diagnostics);
        return new ValidationResult(merged);
    }
    
    @Override
    public String toString() {
        if (diagnostics.isEmpty()) {
            return "ValidationResult(valid)";
        }
        return String.format("ValidationResult(errors=%d, warnings=%d)", 
            errors().size(), warnings().size());
    }
    
    /**
     * Builder for constructing ValidationResult instances.
     */
    public static final class Builder {
        private final List<Diagnostic> diagnostics = new ArrayList<>();
        
        public Builder addDiagnostic(Diagnostic diagnostic) {
            diagnostics.add(diagnostic);
            return this;
        }
        
        public Builder addError(String code, String message, String coordinate, String schemaName) {
            return addDiagnostic(Diagnostic.error(code, message, coordinate, schemaName));
        }
        
        public Builder addError(String code, String message, String coordinate, String schemaName, String member) {
            return addDiagnostic(Diagnostic.error(code, message, coordinate, schemaName, member));
        }
        
        public Builder addWarning(String code, String message, String coordinate, String schemaName) {
            return addDiagnostic(Diagnostic.warning(code, message, coordinate, schemaName));
        }
        
        public ValidationResult build() {
            return new ValidationResult(diagnostics);
        }
    }
    
    /**
     * Creates a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }
}
