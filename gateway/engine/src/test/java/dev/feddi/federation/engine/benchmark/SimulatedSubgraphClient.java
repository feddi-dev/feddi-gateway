package dev.feddi.federation.engine.benchmark;

import dev.feddi.federation.engine.executor.BatchingOptions;
import dev.feddi.federation.engine.executor.SubgraphClient;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.language.AstPrinter;
import graphql.language.Document;
import graphql.language.OperationDefinition;
import graphql.schema.GraphQLSchema;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A subgraph client that executes every incoming operation against a real, executable
 * subgraph schema and records each call. Unlike the fixture-based mocks, it accepts any
 * operation the planner generates, so tests stay valid when plans change.
 *
 * <p>Optionally adds a random latency per call so that subgraph responses complete in
 * varying order, which exposes ordering bugs in the executor.
 */
public final class SimulatedSubgraphClient implements SubgraphClient {

    /**
     * One recorded subgraph call.
     */
    public record Call(String operation, Map<String, Object> variables) {
    }

    private final GraphQL graphQL;
    private final int maxLatencyMillis;
    private final BatchingOptions batching;
    private final List<Call> calls = new CopyOnWriteArrayList<>();

    public SimulatedSubgraphClient(GraphQLSchema schema, int maxLatencyMillis) {
        this(schema, maxLatencyMillis, BatchingOptions.NONE);
    }

    public SimulatedSubgraphClient(GraphQLSchema schema, int maxLatencyMillis, BatchingOptions batching) {
        this.graphQL = GraphQL.newGraphQL(schema).build();
        this.maxLatencyMillis = maxLatencyMillis;
        this.batching = batching;
    }

    @Override
    public BatchingOptions batching() {
        return batching;
    }

    /**
     * Variable batching like a HotChocolate subgraph: one request, one result per variable set.
     */
    @Override
    public Mono<List<ExecutionResult>> executeBatch(OperationDefinition operation,
                                                    List<Map<String, Object>> variableSets) {
        if (batching.mode() != BatchingOptions.Mode.VARIABLES) {
            return Mono.error(new IllegalStateException("subgraph does not support variable batching"));
        }
        calls.add(new Call(AstPrinter.printAstCompact(operation), Map.of("variableSets", variableSets)));
        String query = AstPrinter.printAst(Document.newDocument().definition(operation).build());
        Mono<List<ExecutionResult>> results = Mono.fromCallable(() -> variableSets.stream()
            .map(variables -> graphQL.execute(ExecutionInput.newExecutionInput().query(query).variables(variables).build()))
            .toList());
        return delay().then(results);
    }

    private Mono<Long> delay() {
        if (maxLatencyMillis <= 0) {
            return Mono.just(0L);
        }
        return Mono.delay(Duration.ofMillis(ThreadLocalRandom.current().nextLong(maxLatencyMillis + 1L)), Schedulers.parallel());
    }

    @Override
    public Mono<ExecutionResult> execute(OperationDefinition operation, Map<String, Object> variables) {
        String text = AstPrinter.printAstCompact(operation);
        calls.add(new Call(text, variables));
        String query = AstPrinter.printAst(Document.newDocument().definition(operation).build());
        Mono<ExecutionResult> result = Mono.fromCallable(() -> graphQL.execute(ExecutionInput.newExecutionInput()
            .query(query)
            .variables(variables == null ? Map.of() : variables)
            .build()));
        return delay().then(result);
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }
}
