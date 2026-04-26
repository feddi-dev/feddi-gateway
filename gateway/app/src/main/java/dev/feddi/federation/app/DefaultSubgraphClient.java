package dev.feddi.federation.app;

import dev.feddi.federation.customization.GatewayRequestContext;
import dev.feddi.federation.customization.SubgraphClient;
import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.language.AstPrinter;
import graphql.language.OperationDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Default SubgraphClient implementation using Spring WebClient.
 * Forwards Authorization and User-Agent headers from the gateway request context.
 */
public class DefaultSubgraphClient implements SubgraphClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultSubgraphClient.class);

    private final WebClient webClient;
    private final String subgraphName;

    public DefaultSubgraphClient(WebClient webClient, String subgraphName) {
        this.webClient = webClient;
        this.subgraphName = subgraphName;
    }

    @Override
    public Mono<ExecutionResult> execute(OperationDefinition operation, Map<String, Object> variables, GatewayRequestContext context) {
        String query = AstPrinter.printAst(operation);

        log.debug("[{}] Executing subgraph query: {}", subgraphName, query);
        if (variables != null && !variables.isEmpty()) {
            log.debug("[{}] Query variables: {}", subgraphName, variables);
        }

        Map<String, Object> requestBody = Map.of(
            "query", query,
            "variables", variables != null ? variables : Map.of()
        );

        return webClient.post()
            .contentType(MediaType.APPLICATION_JSON)
            .headers(headers -> {
                context.requestHeader("authorization").ifPresent(v -> headers.set("Authorization", v));
                context.requestHeader("user-agent").ifPresent(v -> headers.set("User-Agent", v));
            })
            .bodyValue(requestBody)
            .exchangeToMono(response -> {
                if (response.statusCode().is2xxSuccessful()) {
                    return response.bodyToMono(Map.class)
                        .doOnNext(body -> {
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> errors = (List<Map<String, Object>>) body.get("errors");
                            if (errors != null && !errors.isEmpty()) {
                                log.info("[{}] Subgraph returned {} GraphQL error(s)", subgraphName, errors.size());
                                for (Map<String, Object> error : errors) {
                                    log.info("[{}] Subgraph error: {}", subgraphName, error.get("message"));
                                }
                            } else {
                                log.debug("[{}] Subgraph call successful", subgraphName);
                            }
                        })
                        .map(body -> buildExecutionResult(body))
                        .defaultIfEmpty(emptyResult());
                } else {
                    log.warn("[{}] Subgraph returned HTTP error: {}", subgraphName, response.statusCode());
                    return response.bodyToMono(Map.class)
                        .doOnNext(body -> log.warn("[{}] Error response body: {}", subgraphName, body))
                        .<ExecutionResult>flatMap(body -> {
                            String errorMsg = extractErrorMessage(body);
                            log.error("[{}] Subgraph call failed: {}", subgraphName, errorMsg);
                            return Mono.error(new RuntimeException("Subgraph call failed: " + errorMsg));
                        })
                        .switchIfEmpty(Mono.defer(() -> {
                            log.error("[{}] Subgraph call failed with empty body: {}", subgraphName, response.statusCode());
                            return Mono.error(new RuntimeException(
                                "Subgraph call failed: " + response.statusCode()));
                        }));
                }
            })
            .doOnError(e -> log.error("[{}] Subgraph call exception: {}", subgraphName, e.getMessage(), e));
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

    private String extractErrorMessage(Map<?, ?> body) {
        Object errors = body.get("errors");
        if (errors instanceof List<?> errorList && !errorList.isEmpty()) {
            Object firstError = errorList.get(0);
            if (firstError instanceof Map<?, ?> errorMap) {
                Object message = errorMap.get("message");
                if (message != null) {
                    return message.toString();
                }
            }
        }
        return "Unknown error";
    }

    private ExecutionResult emptyResult() {
        return ExecutionResultImpl.newExecutionResult()
            .data(Map.of())
            .build();
    }

    private record SubgraphError(
        String message,
        List<Object> path,
        Map<String, Object> extensions
    ) implements graphql.GraphQLError {
        @Override
        public String getMessage() {
            return message;
        }

        @Override
        public List<graphql.language.SourceLocation> getLocations() {
            return null;
        }

        @Override
        public graphql.ErrorClassification getErrorType() {
            return graphql.ErrorType.DataFetchingException;
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
