package dev.feddi.federation.app;

import dev.feddi.federation.engine.executor.ResponseWriter;
import dev.feddi.federation.extension.ExecutionOutcome;
import dev.feddi.federation.extension.FeddiGatewayRequestContext;
import dev.feddi.federation.extension.UsageReporter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.ExecutionInput;
import graphql.GraphqlErrorBuilder;
import graphql.language.OperationDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.server.UnsupportedMediaTypeStatusException;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * HTTP handler for GraphQL requests, as a functional endpoint: the body is read as bytes and parsed directly,
 * without the argument resolution and message decoding of an annotated controller.
 * Collects usage data and reports it via {@link UsageReporter}.
 */
@Component
public class GraphQLController {

    private static final Logger log = LoggerFactory.getLogger(GraphQLController.class);
    private static final ObjectMapper JSON = new ObjectMapper()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    /** The response to a request whose execution failed with an exception. */
    private static final byte[] INTERNAL_ERROR = ResponseWriter.writeErrors(List.of(GraphqlErrorBuilder.newError()
        .message("Internal server error")
        .build()), (bytes, length, errors) -> Arrays.copyOf(bytes, length));

    private final FeddiGatewayHolder gatewayHolder;
    private final UsageReporter usageReporter;
    private final FeddiGatewayMetrics gatewayMetrics;

    public GraphQLController(FeddiGatewayHolder gatewayHolder, UsageReporter usageReporter, FeddiGatewayMetrics gatewayMetrics) {
        this.gatewayHolder = gatewayHolder;
        this.usageReporter = usageReporter;
        this.gatewayMetrics = gatewayMetrics;
    }

    /** Handles {@code POST /graphql} (routed in {@link FeddiGatewayConfig}). */
    public Mono<ServerResponse> handle(ServerRequest httpRequest) {
        if (!httpRequest.headers().contentType().map(MediaType.APPLICATION_JSON::isCompatibleWith).orElse(false)) {
            return Mono.error(new UnsupportedMediaTypeStatusException(httpRequest.headers().contentType().orElse(null),
                List.of(MediaType.APPLICATION_JSON)));
        }
        DataBufferFactory buffers = httpRequest.exchange().getResponse().bufferFactory();
        // The body is read before the response is created: a failure while reading (a body above the size limit)
        // must not happen while the response is being written, or Netty releases the request's buffers twice
        return httpRequest.bodyToMono(byte[].class)
            .switchIfEmpty(Mono.error(() -> new ServerWebInputException("Request body is missing")))
            .map(GraphQLController::readRequest)
            .flatMap(request -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromDataBuffers(executeQuery(request, httpRequest, buffers))));
    }

    private static GraphQLRequest readRequest(byte[] bytes) {
        try {
            return JSON.readValue(bytes, GraphQLRequest.class);
        } catch (IOException e) {
            throw new ServerWebInputException("Invalid GraphQL request body", null, e);
        }
    }

    private Mono<DataBuffer> executeQuery(GraphQLRequest request, ServerRequest httpRequest, DataBufferFactory buffers) {
        String clientName = httpRequest.headers().firstHeader("graphql-client-name");
        String clientVersion = httpRequest.headers().firstHeader("graphql-client-version");

        FeddiFederationGateway gateway = gatewayHolder.get();
        if (gateway == null) {
            log.warn("GraphQL request received but feddi Gateway not initialized");
            return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "feddi Gateway not initialized. Please upload subgraph configuration."));
        }

        log.debug("Executing GraphQL query: {}", request.query());

        var metricsSample = gatewayMetrics.startTimer();

        ExecutionInput executionInput = ExecutionInput.newExecutionInput()
            .query(request.query() != null ? request.query() : "")
            .variables(request.variables() != null ? request.variables() : Map.of())
            .operationName(request.operationName())
            .extensions(request.extensions() != null ? request.extensions() : Map.of())
            .build();

        // Build immutable request context
        var headerMap = new java.util.LinkedHashMap<String, String>();
        httpRequest.headers().asHttpHeaders().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headerMap.put(name, values.getFirst());
            }
        });
        var requestContext = FeddiGatewayRequestContext.builder(headerMap)
            .schema(gateway.supergraph())
            .operationName(request.operationName())
            .variables(request.variables())
            .clientName(clientName)
            .clientVersion(clientVersion)
            .build();

        long startNanos = System.nanoTime();

        return gateway.execute(executionInput, requestContext)
            .map(gatewayResult -> {
                Response response = gatewayResult.toJson((bytes, length, errors) ->
                    new Response(copy(bytes, length, buffers), errors));
                long durationNanos = System.nanoTime() - startNanos;
                int errorCount = response.errorCount();

                // Extract operationType from the parsed document
                String opType = "QUERY";
                if (gatewayResult.document() != null) {
                    var opDef = gatewayResult.document()
                        .getDefinitionsOfType(OperationDefinition.class)
                        .stream().findFirst().orElse(null);
                    if (opDef != null) {
                        opType = opDef.getOperation().name();
                    }
                }

                // Report usage with the parsed document in the outcome
                try {
                    usageReporter.report(requestContext, new ExecutionOutcome(
                        durationNanos,
                        false,
                        errorCount > 0,
                        gatewayResult.document()
                    ));
                } catch (Exception e) {
                    log.debug("Usage reporting failed: {}", e.getMessage());
                }

                gatewayMetrics.recordRequestDuration(metricsSample, opType);
                if (errorCount > 0) {
                    gatewayMetrics.recordRequestError();
                    log.info("GraphQL execution completed with {} error(s)", errorCount);
                } else {
                    log.debug("GraphQL execution completed successfully");
                }
                return response.body();
            })
            .onErrorResume(e -> {
                gatewayMetrics.recordRequestDuration(metricsSample, "QUERY");
                gatewayMetrics.recordRequestError();
                // Generic message — the underlying exception detail goes only to
                // the server log to avoid leaking stack-frame hints, JDBC strings,
                // file paths, or other internal state to clients.
                log.error("GraphQL execution failed with exception", e);
                return Mono.just(copy(INTERNAL_ERROR, INTERNAL_ERROR.length, buffers));
            });
    }

    /**
     * The written response copied into a buffer of the server (pooled and direct with Netty), which the server
     * releases once it is sent. The writer's bytes are reused for the next response, and a heap buffer would be
     * copied into a direct one before the socket write anyway.
     */
    private static DataBuffer copy(byte[] bytes, int length, DataBufferFactory buffers) {
        return buffers.allocateBuffer(length).write(bytes, 0, length);
    }

    /** The response body and the number of its errors. */
    private record Response(DataBuffer body, int errorCount) {
    }

    /**
     * GraphQL request payload.
     */
    public record GraphQLRequest(
        String query,
        Map<String, Object> variables,
        String operationName,
        Map<String, Object> extensions
    ) {}
}
