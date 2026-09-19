package dev.feddi.federation.extension;

import graphql.schema.GraphQLSchema;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable context for a feddi Gateway request, carrying HTTP headers and request metadata.
 * Created before execution. Use the builder-style setters to configure, then pass to the gateway.
 * The document is NOT part of the context — it's a result of execution and lives in {@link ExecutionOutcome}.
 */
public class FeddiGatewayRequestContext {

    private final Map<String, String> requestHeaders;
    private final GraphQLSchema schema;
    private final String operationName;
    private final Map<String, Object> variables;
    private final String clientName;
    private final String clientVersion;

    private FeddiGatewayRequestContext(Map<String, String> requestHeaders, GraphQLSchema schema,
                                       String operationName, Map<String, Object> variables,
                                       String clientName, String clientVersion) {
        var normalized = new LinkedHashMap<String, String>();
        requestHeaders.forEach((k, v) -> normalized.put(k.toLowerCase(), v));
        this.requestHeaders = Map.copyOf(normalized);
        this.schema = schema;
        this.operationName = operationName;
        this.variables = variables;
        this.clientName = clientName;
        this.clientVersion = clientVersion;
    }

    public Map<String, String> requestHeaders() { return requestHeaders; }

    public Optional<String> requestHeader(String name) {
        return Optional.ofNullable(requestHeaders.get(name.toLowerCase()));
    }

    public GraphQLSchema schema() { return schema; }
    public String operationName() { return operationName; }
    public Map<String, Object> variables() { return variables; }
    public String clientName() { return clientName; }
    public String clientVersion() { return clientVersion; }

    public static FeddiGatewayRequestContext empty() {
        return new Builder(Map.of()).build();
    }

    public static Builder builder(Map<String, String> requestHeaders) {
        return new Builder(requestHeaders);
    }

    public static class Builder {
        private final Map<String, String> requestHeaders;
        private GraphQLSchema schema;
        private String operationName;
        private Map<String, Object> variables;
        private String clientName;
        private String clientVersion;

        private Builder(Map<String, String> requestHeaders) {
            this.requestHeaders = requestHeaders;
        }

        public Builder schema(GraphQLSchema schema) { this.schema = schema; return this; }
        public Builder operationName(String operationName) { this.operationName = operationName; return this; }
        public Builder variables(Map<String, Object> variables) { this.variables = variables; return this; }
        public Builder clientName(String clientName) { this.clientName = clientName; return this; }
        public Builder clientVersion(String clientVersion) { this.clientVersion = clientVersion; return this; }

        public FeddiGatewayRequestContext build() {
            return new FeddiGatewayRequestContext(requestHeaders, schema, operationName, variables, clientName, clientVersion);
        }
    }
}
