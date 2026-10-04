package dev.feddi.federation.engine.benchmark;

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
    private final List<Call> calls = new CopyOnWriteArrayList<>();

    public SimulatedSubgraphClient(GraphQLSchema schema, int maxLatencyMillis) {
        this.graphQL = GraphQL.newGraphQL(schema).build();
        this.maxLatencyMillis = maxLatencyMillis;
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
        if (maxLatencyMillis <= 0) {
            return result;
        }
        long delay = ThreadLocalRandom.current().nextLong(maxLatencyMillis + 1L);
        return Mono.delay(Duration.ofMillis(delay), Schedulers.parallel()).then(result);
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }
}
