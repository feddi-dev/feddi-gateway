package dev.feddi.federation.engine.planner;

/**
 * Metadata for a non-null {@code @require} argument used when the resolved value is null.
 *
 * @param fieldName the field that owns the {@code @require} argument
 * @param fieldReturnNonNull true when that field's return type is non-null (Case 3 → emit error)
 */
public record RequireArgumentSkipInfo(String fieldName, boolean fieldReturnNonNull) {
    public RequireArgumentSkipInfo {
        if (fieldName == null || fieldName.isBlank()) {
            throw new IllegalArgumentException("fieldName cannot be null or blank");
        }
    }
}
