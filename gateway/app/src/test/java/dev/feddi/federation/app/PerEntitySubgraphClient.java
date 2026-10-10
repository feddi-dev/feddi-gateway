package dev.feddi.federation.app;

import dev.feddi.federation.extension.FeddiGatewayRequestContext;
import dev.feddi.federation.extension.SubgraphClient;
import graphql.ExecutionResult;
import graphql.language.OperationDefinition;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * A {@link SubgraphClient} that can be written as a lambda in tests: batches are sent as one
 * request per variable set.
 */
@FunctionalInterface
interface PerEntitySubgraphClient extends SubgraphClient {

    @Override
    default Mono<List<ExecutionResult>> executeBatch(OperationDefinition operation,
                                                     List<Map<String, Object>> variableSets,
                                                     FeddiGatewayRequestContext context) {
        return SubgraphClient.executeEach(this, operation, variableSets, context);
    }
}
