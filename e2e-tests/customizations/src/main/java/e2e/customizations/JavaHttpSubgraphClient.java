package e2e.customizations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.feddi.federation.customization.SubgraphClient;
import graphql.ErrorClassification;
import graphql.ErrorType;
import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.GraphQLError;
import graphql.language.AstPrinter;
import graphql.language.OperationDefinition;
import graphql.language.SourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

/**
 * SubgraphClient implementation that uses Java's built-in HttpClient.
 */
public class JavaHttpSubgraphClient implements SubgraphClient {

    private static final Logger logger = LoggerFactory.getLogger(JavaHttpSubgraphClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String url;
    private final String subgraphName;

    public JavaHttpSubgraphClient(HttpClient httpClient, ObjectMapper objectMapper, String url, String subgraphName) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.url = url;
        this.subgraphName = subgraphName;
    }

    @Override
    public Mono<ExecutionResult> execute(OperationDefinition operation, Map<String, Object> variables, dev.feddi.federation.customization.GatewayRequestContext context) {
        String query = AstPrinter.printAst(operation);

        logger.info("[JavaHttpSubgraphClient] Executing query on subgraph '{}': {}", subgraphName, query);

        Map<String, Object> requestBody = Map.of(
            "query", query,
            "variables", variables != null ? variables : Map.of()
        );

        return Mono.fromCallable(() -> {
            String jsonBody = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            logger.info("[JavaHttpSubgraphClient] Received response from subgraph '{}': status={}",
                subgraphName, response.statusCode());

            @SuppressWarnings("unchecked")
            Map<String, Object> responseMap = objectMapper.readValue(response.body(), Map.class);
            return buildExecutionResult(responseMap);
        });
    }

    private ExecutionResult buildExecutionResult(Map<?, ?> response) {
        Object data = response.get("data");
        Object errors = response.get("errors");

        ExecutionResultImpl.Builder builder = ExecutionResultImpl.newExecutionResult();

        if (data != null) {
            builder.data(data);
        }

        if (errors instanceof List) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> errorList = (List<Map<String, Object>>) errors;
            for (Map<String, Object> error : errorList) {
                String message = error.get("message") != null ? error.get("message").toString() : "Unknown error";

                @SuppressWarnings("unchecked")
                List<Object> path = (List<Object>) error.get("path");

                @SuppressWarnings("unchecked")
                Map<String, Object> extensions = (Map<String, Object>) error.get("extensions");

                builder.addError(new SubgraphError(message, path, extensions));
            }
        }

        return builder.build();
    }

    /**
     * GraphQL error from subgraph response, preserving path and extensions.
     */
    private record SubgraphError(
        String message,
        List<Object> path,
        Map<String, Object> extensions
    ) implements GraphQLError {
        @Override
        public String getMessage() {
            return message;
        }

        @Override
        public List<SourceLocation> getLocations() {
            return null;
        }

        @Override
        public ErrorClassification getErrorType() {
            return ErrorType.DataFetchingException;
        }

        @Override
        public List<Object> getPath() {
            return path;
        }

        @Override
        public Map<String, Object> getExtensions() {
            return extensions;
        }
    }
}
