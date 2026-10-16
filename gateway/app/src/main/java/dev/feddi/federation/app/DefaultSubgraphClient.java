package dev.feddi.federation.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.feddi.federation.engine.IntrospectionFields;
import dev.feddi.federation.engine.executor.JsonTreeReader;
import dev.feddi.federation.engine.executor.OperationTexts;
import dev.feddi.federation.extension.FeddiGatewayRequestContext;
import dev.feddi.federation.extension.SubgraphClient;
import dev.feddi.federation.extension.SubgraphRequestHeaderCustomizer;
import graphql.Directives;
import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.language.OperationDefinition;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaderNames;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.support.Netty4HeadersAdapter;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.util.context.Context;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Default SubgraphClient implementation: GraphQL over HTTP with Reactor Netty's {@link HttpClient}, request bodies
 * written as JSON bytes and responses read from bytes.
 * Forwards Authorization and User-Agent headers from the gateway request context, then
 * gives an optional {@link SubgraphRequestHeaderCustomizer} bean a chance to add or
 * override headers.
 */
public class DefaultSubgraphClient implements SubgraphClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultSubgraphClient.class);

    private static final String JSON_TYPE = "application/json";
    private static final String BATCH_ACCEPT = "application/jsonl, application/json";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final byte[] EMPTY = new byte[0];
    private static final String PROBE_VARIABLE = "probe";
    /**
     * Caches the subscriber context at the end of a subgraph call. Reactor Netty reads the context several times per
     * request, and each read walks up the whole chain of the gateway's operators otherwise.
     */
    private static final Function<Context, Context> CACHE_CONTEXT = context -> context;

    private final HttpClient httpClient;
    /** The path (and query) of the subgraph URL; the client is configured with its scheme, host and port. */
    private final String path;
    private final String subgraphName;
    private final @Nullable SubgraphRequestHeaderCustomizer headerCustomizer;

    public DefaultSubgraphClient(HttpClient httpClient, String url, String subgraphName,
                                  @Nullable SubgraphRequestHeaderCustomizer headerCustomizer) {
        // Configured once: with an absolute URL, Reactor Netty parses it with a regular expression on every request
        URI uri = URI.create(url);
        if (uri.getHost() == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
            throw new IllegalArgumentException("Subgraph " + subgraphName + " has no valid http(s) URL: " + url);
        }
        boolean secure = "https".equals(uri.getScheme());
        String host = uri.getHost().startsWith("[") ? uri.getHost().substring(1, uri.getHost().length() - 1)
            : uri.getHost();
        HttpClient configured = httpClient.host(host).port(uri.getPort() >= 0 ? uri.getPort() : secure ? 443 : 80);
        this.httpClient = secure ? configured.secure() : configured;
        String rawPath = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        this.path = uri.getRawQuery() == null ? rawPath : rawPath + "?" + uri.getRawQuery();
        this.subgraphName = subgraphName;
        this.headerCustomizer = headerCustomizer;
    }

    /** A subgraph's HTTP response: its status and body (empty if it had none). */
    private record HttpResponse(int status, byte[] body) {
        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    /**
     * Posts a JSON body to the subgraph.
     *
     * @param batch whether it is a variable batch, which accepts JSON lines
     */
    private Mono<HttpResponse> post(byte[] body, boolean batch, FeddiGatewayRequestContext context) {
        return httpClient.post()
            .uri(path)
            .send((request, out) -> {
                request.requestHeaders()
                    .set(HttpHeaderNames.CONTENT_TYPE, JSON_TYPE)
                    .set(HttpHeaderNames.ACCEPT, batch ? BATCH_ACCEPT : JSON_TYPE);
                applyHeaders(request.requestHeaders(), context);
                return out.send(Mono.just(Unpooled.wrappedBuffer(body)));
            })
            .responseSingle((response, content) -> content.asByteArray()
                .defaultIfEmpty(EMPTY)
                .map(bytes -> new HttpResponse(response.status().code(), bytes)));
    }

    @Override
    public Mono<ExecutionResult> execute(OperationDefinition operation, Map<String, Object> variables, FeddiGatewayRequestContext context) {
        if (log.isDebugEnabled()) {
            log.debug("[{}] Executing subgraph query: {}", subgraphName, OperationTexts.pretty(operation));
            if (variables != null && !variables.isEmpty()) {
                log.debug("[{}] Query variables: {}", subgraphName, variables);
            }
        }

        return post(OperationTexts.requestBody(operation, variables != null ? variables : Map.of()), false, context)
            .flatMap(response -> {
                if (response.body().length == 0) {
                    if (response.ok()) {
                        return Mono.just(emptyResult());
                    }
                    log.error("[{}] Subgraph call failed with empty body: {}", subgraphName, response.status());
                    return Mono.error(new RuntimeException("Subgraph call failed: " + response.status()));
                }
                Map<String, Object> body;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> object = JsonTreeReader.read(response.body()) instanceof Map<?, ?> map
                        ? (Map<String, Object>) map : null;
                    body = object;
                } catch (IOException e) {
                    if (response.ok()) {
                        return Mono.error(new RuntimeException(
                            "Subgraph " + subgraphName + " returned invalid JSON: " + e.getMessage(), e));
                    }
                    log.error("[{}] Subgraph call failed: {}", subgraphName, response.status());
                    return Mono.error(new RuntimeException("Subgraph call failed: " + response.status()));
                }
                if (body == null) {
                    return Mono.error(new RuntimeException(
                        "Subgraph " + subgraphName + " returned no JSON object: " + response.status()));
                }
                if (!response.ok()) {
                    log.warn("[{}] Subgraph returned HTTP error: {}", subgraphName, response.status());
                    log.warn("[{}] Error response body: {}", subgraphName, body);
                    String errorMsg = extractErrorMessage(body);
                    log.error("[{}] Subgraph call failed: {}", subgraphName, errorMsg);
                    return Mono.error(new RuntimeException("Subgraph call failed: " + errorMsg));
                }
                logErrors(body);
                return Mono.just(buildExecutionResult(body));
            })
            .doOnError(e -> log.error("[{}] Subgraph call exception: {}", subgraphName, e.getMessage(), e))
            .contextWrite(CACHE_CONTEXT);
    }

    private void logErrors(Map<String, Object> body) {
        if (body.get("errors") instanceof List<?> errors && !errors.isEmpty()) {
            log.info("[{}] Subgraph returned {} GraphQL error(s)", subgraphName, errors.size());
            for (Object error : errors) {
                log.info("[{}] Subgraph error: {}", subgraphName,
                    error instanceof Map<?, ?> map ? map.get("message") : error);
            }
        } else {
            log.debug("[{}] Subgraph call successful", subgraphName);
        }
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
        if (log.isDebugEnabled()) {
            log.debug("[{}] Executing variable batch of {}: {}", subgraphName, variableSets.size(),
                OperationTexts.pretty(operation));
        }

        return post(OperationTexts.requestBody(operation, variableSets), true, context)
            .flatMap(response -> {
                if (!response.ok()) {
                    log.warn("[{}] Variable batch returned HTTP {}: {}", subgraphName, response.status(),
                        new String(response.body(), StandardCharsets.UTF_8));
                    return Mono.error(new RuntimeException(
                        "Subgraph variable batch failed: " + response.status()));
                }
                try {
                    return Mono.just(parseBatchResponse(response.body(), variableSets.size()).stream()
                        .map(this::buildExecutionResult)
                        .toList());
                } catch (IllegalArgumentException e) {
                    return Mono.error(new RuntimeException(
                        "Subgraph " + subgraphName + " returned an invalid variable batch response: " + e.getMessage(), e));
                }
            })
            .doOnError(e -> log.error("[{}] Variable batch exception: {}", subgraphName, e.getMessage(), e))
            .contextWrite(CACHE_CONTEXT);
    }

    /**
     * Checks whether the subgraph supports variable batching by sending a minimal batch of two.
     * The probe declares and uses a variable, like the lookups it stands for: a server may answer
     * an operation without variables once, whatever the variables array (HotChocolate 16.6 does).
     *
     * <p>Answers that say nothing about batching are inconclusive: authentication failures (the
     * probe carries no client's {@code Authorization}), rate limiting, timeouts and server errors.
     *
     * @return true if supported, false if the subgraph rejected the batch or did not answer with
     *         two batch results; an error if the result is inconclusive or the subgraph could not be
     *         reached
     */
    Mono<Boolean> supportsVariableBatching() {
        Map<String, Object> requestBody = Map.of(
            "query", "query ($" + PROBE_VARIABLE + ": Boolean!) { " + IntrospectionFields.TYPENAME
                + " @" + Directives.IncludeDirective.getName() + "(if: $" + PROBE_VARIABLE + ") }",
            "variables", List.of(Map.of(PROBE_VARIABLE, true), Map.of(PROBE_VARIABLE, true)));
        byte[] body;
        try {
            body = JSON.writeValueAsBytes(requestBody);
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
        // Not a client request: only the customizer's headers (e.g. internal auth) apply.
        return post(body, true, FeddiGatewayRequestContext.empty())
            .flatMap(response -> {
                int status = response.status();
                if (isInconclusiveProbeStatus(status)) {
                    return Mono.error(new IllegalStateException("probe returned HTTP " + status));
                }
                if (!response.ok()) {
                    return Mono.just(false);
                }
                try {
                    return Mono.just(parseBatchResponse(response.body(), 2).stream()
                        .allMatch(r -> r.get("data") != null));
                } catch (IllegalArgumentException e) {
                    return Mono.just(false);
                }
            });
    }

    private static boolean isInconclusiveProbeStatus(int status) {
        return status == 401 || status == 403 || status == 408 || status == 429 || status >= 500;
    }

    /**
     * Forwards Authorization and User-Agent from the gateway request, then lets the optional
     * {@link SubgraphRequestHeaderCustomizer} add or override headers.
     */
    private void applyHeaders(io.netty.handler.codec.http.HttpHeaders headers, FeddiGatewayRequestContext context) {
        context.requestHeader("authorization").ifPresent(v -> headers.set(HttpHeaderNames.AUTHORIZATION, v));
        context.requestHeader("user-agent").ifPresent(v -> headers.set(HttpHeaderNames.USER_AGENT, v));
        if (headerCustomizer != null) {
            headerCustomizer.customize(new HttpHeaders(new Netty4HeadersAdapter(headers)), subgraphName, context);
        }
    }

    /**
     * Parses a variable batch response into one response map per variable set, in order.
     *
     * @throws IllegalArgumentException if the body is not a valid batch response of the expected size
     */
    static List<Map<String, Object>> parseBatchResponse(String body, int expected) {
        return parseBatchResponse(body.getBytes(StandardCharsets.UTF_8), expected);
    }

    /**
     * Parses a variable batch response: a JSON array in order, or JSON lines with a
     * {@code variableIndex}. JSON lines are read as a sequence of root-level values straight
     * from the bytes; whitespace (any line ending) separates them.
     *
     * @throws IllegalArgumentException if the body is not a valid batch response of the expected size
     */
    static List<Map<String, Object>> parseBatchResponse(byte[] body, int expected) {
        List<Map<String, Object>> responses = new ArrayList<>(expected);
        try {
            if (startsWithArray(body)) {
                if (!(JsonTreeReader.read(body) instanceof List<?> array)) {
                    throw new IllegalArgumentException("not a JSON array");
                }
                for (Object item : array) {
                    responses.add(asObject(item));
                }
            } else {
                @SuppressWarnings("unchecked")
                Map<String, Object>[] ordered = new Map[expected];
                for (Object line : JsonTreeReader.readSequence(body)) {
                    Map<String, Object> response = asObject(line);
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
        } catch (IOException e) {
            throw new IllegalArgumentException("not JSON: " + e.getMessage(), e);
        }
        if (responses.size() != expected) {
            throw new IllegalArgumentException("expected " + expected + " results, got " + responses.size());
        }
        return responses;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("not a JSON object: " + value);
        }
        return (Map<String, Object>) map;
    }

    private static boolean startsWithArray(byte[] body) {
        for (byte b : body) {
            if (!Character.isWhitespace(b)) {
                return b == '[';
            }
        }
        return false;
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
