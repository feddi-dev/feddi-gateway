package dev.feddi.federation.engine.executor;

import graphql.ErrorClassification;
import graphql.GraphQLError;
import graphql.language.SourceLocation;

import java.util.List;
import java.util.Map;

/**
 * GraphQL error indicating that a subgraph call timed out.
 */
public record SubgraphTimeoutError(String subgraphName) implements GraphQLError {

    private static final String ERROR_CODE = "SUBGRAPH_TIMEOUT";

    @Override
    public String getMessage() {
        return "Subgraph '" + subgraphName + "' timed out";
    }

    @Override
    public List<SourceLocation> getLocations() {
        return null;
    }

    @Override
    public ErrorClassification getErrorType() {
        return ErrorClassification.errorClassification("SUBGRAPH_TIMEOUT");
    }

    @Override
    public Map<String, Object> getExtensions() {
        return Map.of(
            "code", ERROR_CODE,
            "subgraph", subgraphName
        );
    }
}
