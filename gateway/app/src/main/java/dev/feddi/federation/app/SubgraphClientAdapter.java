package dev.feddi.federation.app;

import dev.feddi.federation.customization.GatewayRequestContext;
import dev.feddi.federation.customization.SubgraphClient;
import graphql.ExecutionResult;
import graphql.language.OperationDefinition;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Adapts a customization-api SubgraphClient to the engine's SubgraphClient interface.
 *
 * <p>Captures the {@link GatewayRequestContext} at creation time (per-request)
 * and passes it to the delegate on every execute call. The engine's interface
 * stays unchanged — it doesn't know about the request context.
 */
public class SubgraphClientAdapter implements dev.feddi.federation.engine.executor.SubgraphClient {

    private final SubgraphClient delegate;
    private final GatewayRequestContext context;

    public SubgraphClientAdapter(SubgraphClient delegate, GatewayRequestContext context) {
        this.delegate = delegate;
        this.context = context;
    }

    @Override
    public Mono<ExecutionResult> execute(OperationDefinition operation, Map<String, Object> variables) {
        return delegate.execute(operation, variables, context);
    }
}
