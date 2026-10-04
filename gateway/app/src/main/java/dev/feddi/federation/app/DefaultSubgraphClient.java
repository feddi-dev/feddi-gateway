package dev.feddi.federation.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.feddi.federation.engine.IntrospectionFields;
import dev.feddi.federation.engine.executor.OperationTexts;
import dev.feddi.federation.extension.FeddiGatewayRequestContext;
import dev.feddi.federation.extension.SubgraphClient;
import dev.feddi.federation.extension.SubgraphRequestHeaderCustomizer;
import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.language.OperationDefinition;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Default SubgraphClient implementation using Spring WebClient.
 * Forwards Authorization and User-Agent headers from the gateway request context, then
 * gives an optional {@link SubgraphRequestHeaderCustomizer} bean a chance to add or
 * override headers.
 */
public class DefaultSubgraphClient implements SubgraphClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultSubgraphClient.class);

    private static final MediaType JSONL = MediaType.parseMediaType("application/jsonl");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final TypeReference<List<Map<String, Object>>> LIST_OF_MAPS = new TypeReference<>() { };

    private final WebClient webClient;
    private final String subgraphName;
    private final @Nullable SubgraphRequestHeaderCustomizer headerCustomizer;

    public DefaultSubgraphClient(WebClient webClient, String subgraphName,
                                  @Nullable SubgraphRequestHeaderCustomizer headerCustomizer) {
        this.webClient = webClient;
        this.subgraphName = subgraphName;
        this.headerCustomizer = headerCustomizer;
    }

    @Override
    public Mono<ExecutionResult> execute(OperationDefinition operation, Map<String, Object> variables, FeddiGatewayRequestContext context) {
        String query = OperationTexts.pretty(operation);

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
                if (headerCustomizer != null) {
                    headerCustomizer.customize(headers, subgraphName, context);
                }
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

    /**
     * Variable batching: sends one request whose {@code variables} is an array and expects one
     * result per variable set, either as JSON lines with a {@code variableIndex} (HotChocolate,
     * application/jsonl) or as a JSON array in order.
     */
    @Override
    public Mono<List<ExecutionResult>> executeBatch(OperationDefinition operation,
                                                    List<Map<String, Object>> variableSets,
                                                    FeddiGatewayRequestContext context) {
        String query = OperationTexts.pretty(operation);
        log.debug("[{}] Executing variable batch of {}: {}", subgraphName, variableSets.size(), query);

        Map<String, Object> requestBody = Map.of("query", query, "variables", variableSets);

        return webClient.post()
            .contentType(MediaType.APPLICATION_JSON)
            .accept(JSONL, MediaType.APPLICATION_JSON)
            .headers(headers -> {
                context.requestHeader("authorization").ifPresent(v -> headers.set("Authorization", v));
                context.requestHeader("user-agent").ifPresent(v -> headers.set("User-Agent", v));
            })
            .bodyValue(requestBody)
            .exchangeToMono(response -> response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .flatMap(body -> {
                    if (!response.statusCode().is2xxSuccessful()) {
                        log.warn("[{}] Variable batch returned HTTP {}: {}", subgraphName, response.statusCode(), body);
                        return Mono.error(new RuntimeException(
                            "Subgraph variable batch failed: " + response.statusCode()));
                    }
                    try {
                        return Mono.just(parseBatchResponse(body, variableSets.size()).stream()
                            .map(this::buildExecutionResult)
                            .toList());
                    } catch (IllegalArgumentException e) {
                        return Mono.error(new RuntimeException(
                            "Subgraph " + subgraphName + " returned an invalid variable batch response: " + e.getMessage(), e));
                    }
                }))
            .doOnError(e -> log.error("[{}] Variable batch exception: {}", subgraphName, e.getMessage(), e));
    }

    /**
     * Checks whether the subgraph supports variable batching by sending a minimal batch of two.
     *
     * @return true if supported, false if the subgraph answered but not with a valid batch
     *         response; an error if the subgraph could not be reached
     */
    Mono<Boolean> supportsVariableBatching() {
        Map<String, Object> requestBody = Map.of(
            "query", "{ " + IntrospectionFields.TYPENAME + " }",
            "variables", List.of(Map.of(), Map.of()));
        return webClient.post()
            .contentType(MediaType.APPLICATION_JSON)
            .accept(JSONL, MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .exchangeToMono(response -> response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(body -> {
                    if (!response.statusCode().is2xxSuccessful()) {
                        return false;
                    }
                    try {
                        return parseBatchResponse(body, 2).stream().allMatch(r -> r.get("data") != null);
                    } catch (IllegalArgumentException e) {
                        return false;
                    }
                }));
    }

    /**
     * Parses a variable batch response into one response map per variable set, in order.
     *
     * @throws IllegalArgumentException if the body is not a valid batch response of the expected size
     */
    static List<Map<String, Object>> parseBatchResponse(String body, int expected) {
        String trimmed = body.trim();
        List<Map<String, Object>> responses = new ArrayList<>();
        try {
            if (trimmed.startsWith("[")) {
                responses.addAll(JSON.readValue(trimmed, LIST_OF_MAPS));
            } else {
                Map<String, Object>[] ordered = new Map[expected];
                for (String line : trimmed.split("\\R")) {
                    if (line.isBlank()) {
                        continue;
                    }
                    Map<String, Object> response = JSON.readValue(line, MAP);
                    Object index = response.get("variableIndex");
                    if (!(index instanceof Number number) || number.intValue() < 0 || number.intValue() >= expected) {
                        throw new IllegalArgumentException("missing or invalid variableIndex: " + index);
                    }
                    ordered[number.intValue()] = response;
                }
                for (Map<String, Object> response : ordered) {
                    if (response == null) {
                        throw new IllegalArgumentException("missing results");
                    }
                    responses.add(response);
                }
            }
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("not JSON: " + e.getOriginalMessage(), e);
        }
        if (responses.size() != expected) {
            throw new IllegalArgumentException("expected " + expected + " results, got " + responses.size());
        }
        return responses;
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
