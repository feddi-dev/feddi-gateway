package dev.feddi.federation.app;

import dev.feddi.federation.customization.ExecutionOutcome;
import dev.feddi.federation.customization.GatewayRequestContext;
import dev.feddi.federation.customization.UsageReporter;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.GraphqlErrorBuilder;
import graphql.language.OperationDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * HTTP controller for handling GraphQL requests.
 * Collects usage data and reports it via {@link UsageReporter}.
 */
@RestController
public class GraphQLController {

    private static final Logger log = LoggerFactory.getLogger(GraphQLController.class);

    private final GatewayHolder gatewayHolder;
    private final UsageReporter usageReporter;
    private final GatewayMetrics gatewayMetrics;

    public GraphQLController(GatewayHolder gatewayHolder, UsageReporter usageReporter, GatewayMetrics gatewayMetrics) {
        this.gatewayHolder = gatewayHolder;
        this.usageReporter = usageReporter;
        this.gatewayMetrics = gatewayMetrics;
    }

    @PostMapping(value = "/graphql", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> executeQuery(
            @RequestBody GraphQLRequest request,
            @RequestHeader(value = "graphql-client-name", required = false) String clientName,
            @RequestHeader(value = "graphql-client-version", required = false) String clientVersion,
            org.springframework.http.server.reactive.ServerHttpRequest httpRequest) {

        FederationGateway gateway = gatewayHolder.get();
        if (gateway == null) {
            log.warn("GraphQL request received but gateway not initialized");
            return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Gateway not initialized. Please upload subgraph configuration."));
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
        httpRequest.getHeaders().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headerMap.put(name, values.getFirst());
            }
        });
        var requestContext = GatewayRequestContext.builder(headerMap)
            .schema(gateway.supergraph())
            .operationName(request.operationName())
            .variables(request.variables())
            .clientName(clientName)
            .clientVersion(clientVersion)
            .build();

        long startNanos = System.nanoTime();

        return gateway.execute(executionInput, requestContext)
            .doOnNext(gatewayResult -> {
                long durationNanos = System.nanoTime() - startNanos;
                var result = gatewayResult.executionResult();
                int errorCount = (result.getErrors() != null) ? result.getErrors().size() : 0;

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
            })
            .map(gr -> gr.executionResult().toSpecification())
            .onErrorResume(e -> {
                gatewayMetrics.recordRequestDuration(metricsSample, "QUERY");
                gatewayMetrics.recordRequestError();
                // Generic message — the underlying exception detail goes only to
                // the server log to avoid leaking stack-frame hints, JDBC strings,
                // file paths, or other internal state to clients.
                log.error("GraphQL execution failed with exception", e);
                ExecutionResult errorResult = ExecutionResultImpl.newExecutionResult()
                    .addError(GraphqlErrorBuilder.newError()
                        .message("Internal server error")
                        .build())
                    .build();
                return Mono.just(errorResult.toSpecification());
            });
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
