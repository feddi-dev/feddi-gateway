package dev.feddi.federation.engine.executor;

import graphql.ErrorClassification;
import graphql.GraphQLError;
import graphql.language.SourceLocation;

import java.util.List;
import java.util.Map;

/**
 * GraphQL error when a non-null {@code @require} argument resolves to null
 * for a field whose return type is also non-null.
 */
public record RequiredArgumentNullError(String fieldName, String argumentName) implements GraphQLError {

    private static final String ERROR_CODE = "REQUIRED_ARGUMENT_NULL";

    public RequiredArgumentNullError {
        if (fieldName == null || fieldName.isBlank()) {
            throw new IllegalArgumentException("fieldName cannot be null or blank");
        }
        if (argumentName == null || argumentName.isBlank()) {
            throw new IllegalArgumentException("argumentName cannot be null or blank");
        }
    }

    @Override
    public String getMessage() {
        return "Cannot resolve field '" + fieldName + "': required argument '"
            + argumentName + "' resolved to null";
    }

    @Override
    public List<SourceLocation> getLocations() {
        return null;
    }

    @Override
    public ErrorClassification getErrorType() {
        return ErrorClassification.errorClassification(ERROR_CODE);
    }

    @Override
    public Map<String, Object> getExtensions() {
        return Map.of(
            "code", ERROR_CODE,
            "argumentName", argumentName
        );
    }
}
