package dev.feddi.federation.app;

import dev.feddi.federation.engine.testcase.ExecutionTest;
import dev.feddi.federation.engine.testcase.SubgraphCall;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds GraphQL stub configurations from execution test subgraph calls.
 *
 * <p>This replaces the previous WireMock-based stub builder. Instead of string
 * matching, the new GraphQL servers execute actual GraphQL operations and
 * validate them against the subgraph schema.
 */
public final class SubgraphStubBuilder {

    private SubgraphStubBuilder() {}

    /**
     * Configures GraphQL stubs for all subgraph calls in an execution test.
     *
     * @param servers the dynamic subgraph servers
     * @param test the execution test containing subgraph calls
     */
    public static void configureStubs(DynamicSubgraphServers servers, ExecutionTest test) {
        // Clear previous stubs
        servers.resetAllStubs();

        // Group calls by subgraph
        Map<String, List<SubgraphCall>> callsBySubgraph = test.subgraphCalls().stream()
            .collect(Collectors.groupingBy(SubgraphCall::subgraph));

        // Configure each server with its expected calls
        for (Map.Entry<String, List<SubgraphCall>> entry : callsBySubgraph.entrySet()) {
            GraphQLSubgraphServer server = servers.getServer(entry.getKey());
            for (SubgraphCall call : entry.getValue()) {
                server.stubFor(
                    call.operation(),
                    call.variables(),
                    call.response(),
                    call.delayMs(),
                    call.failWithError()
                );
            }
        }
    }
}
