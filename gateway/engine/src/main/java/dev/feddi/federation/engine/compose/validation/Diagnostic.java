package dev.feddi.federation.engine.compose.validation;

/**
 * Represents a validation diagnostic (error, warning, or info).
 *
 * @param code the error code (e.g., "IS_INVALID_USAGE", "LOOKUP_RETURNS_LIST")
 * @param message human-readable error message
 * @param severity the severity level
 * @param coordinate schema coordinate (e.g., "Query.userById", "User.name")
 * @param schemaName the source schema name where the issue was found
 * @param member specific member/directive name (optional)
 */
public record Diagnostic(
    String code,
    String message,
    Severity severity,
    String coordinate,
    String schemaName,
    String member
) {
    
    public Diagnostic {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code cannot be null or blank");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message cannot be null or blank");
        }
        if (severity == null) {
            throw new IllegalArgumentException("severity cannot be null");
        }
    }
    
    /**
     * Creates an error diagnostic.
     */
    public static Diagnostic error(String code, String message, String coordinate, String schemaName) {
        return new Diagnostic(code, message, Severity.ERROR, coordinate, schemaName, null);
    }
    
    /**
     * Creates an error diagnostic with a member.
     */
    public static Diagnostic error(String code, String message, String coordinate, String schemaName, String member) {
        return new Diagnostic(code, message, Severity.ERROR, coordinate, schemaName, member);
    }
    
    /**
     * Creates a warning diagnostic.
     */
    public static Diagnostic warning(String code, String message, String coordinate, String schemaName) {
        return new Diagnostic(code, message, Severity.WARNING, coordinate, schemaName, null);
    }
    
    /**
     * Creates an info diagnostic.
     */
    public static Diagnostic info(String code, String message, String coordinate, String schemaName) {
        return new Diagnostic(code, message, Severity.INFO, coordinate, schemaName, null);
    }
    
    /**
     * Checks if this is an error.
     */
    public boolean isError() {
        return severity == Severity.ERROR;
    }
    
    /**
     * Checks if this is a warning.
     */
    public boolean isWarning() {
        return severity == Severity.WARNING;
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(severity).append("] ").append(code);
        if (schemaName != null) {
            sb.append(" in schema '").append(schemaName).append("'");
        }
        if (coordinate != null) {
            sb.append(" at ").append(coordinate);
        }
        sb.append(": ").append(message);
        return sb.toString();
    }
}
