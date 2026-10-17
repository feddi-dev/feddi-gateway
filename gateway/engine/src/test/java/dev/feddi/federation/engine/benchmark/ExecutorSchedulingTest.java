package dev.feddi.federation.engine.benchmark;

import dev.feddi.federation.engine.executor.BatchingOptions;
import dev.feddi.federation.engine.executor.Executor;
import dev.feddi.federation.engine.executor.SubgraphClient;
import dev.feddi.federation.engine.planner.ExecutionPlan;
import dev.feddi.federation.engine.planner.OperationPlanner;
import dev.feddi.federation.engine.query.Operation;
import dev.feddi.federation.engine.query.OperationNormalizer;
import dev.feddi.federation.engine.testcase.SchemaDefinition;
import dev.feddi.federation.engine.testcase.TestCaseLoader;
import graphql.ExecutionResult;
import graphql.language.OperationDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the executor runs the steps of a plan: cancelling the execution cancels the subgraph calls in flight, and
 * every call is subscribed with the execution's subscriber context (which extensions, e.g. a security context,
 * may read).
 */
class ExecutorSchedulingTest {

    private static final String QUERY = "{ users { id reviews { id product { upc name } } } }";

    private static SchemaDefinition schema;
    private static ExecutionPlan plan;

    @BeforeAll
    static void planQuery() throws Exception {
        schema = new TestCaseLoader().loadSchemaFromClasspath("schemas/benchmark_heavy_query/schema.yaml");
        var normalizer = OperationNormalizer.builder(schema.supergraphSchema())
            .inlineFragments(true).deduplicateFields(true).sortSelections(false).build();
        plan = new OperationPlanner(schema.graph()).plan(Operation.parse(QUERY, normalizer));
    }

    @Test
    void cancellingTheExecutionCancelsCallsInFlight() {
        // (Calls shared between steps are cached and run on; batches are not shared.)
        AtomicInteger cancelled = new AtomicInteger();
        Map<String, SubgraphClient> clients = clients(client -> new DelegatingClient(client) {
            @Override
            public Mono<List<ExecutionResult>> executeBatch(OperationDefinition operation,
                                                            List<Map<String, Object>> variableSets) {
                // The lookup batches never answer
                return Mono.<List<ExecutionResult>>never().doOnCancel(cancelled::incrementAndGet);
            }
        });

        Disposable execution = new Executor(clients).execute(plan, Map.of()).subscribe();
        execution.dispose();

        assertThat(cancelled).hasPositiveValue();
    }

    @Test
    void everyCallSeesTheExecutionsSubscriberContext() {
        List<Object> seen = new CopyOnWriteArrayList<>();
        Map<String, SubgraphClient> clients = clients(client -> new DelegatingClient(client) {
            @Override
            public Mono<ExecutionResult> execute(OperationDefinition operation, Map<String, Object> variables) {
                return Mono.deferContextual(context -> {
                    seen.add(context.getOrDefault("caller", "none"));
                    return super.execute(operation, variables);
                });
            }

            @Override
            public Mono<List<ExecutionResult>> executeBatch(OperationDefinition operation,
                                                            List<Map<String, Object>> variableSets) {
                return Mono.deferContextual(context -> {
                    seen.add(context.getOrDefault("caller", "none"));
                    return super.executeBatch(operation, variableSets);
                });
            }
        });

        ExecutionResult result = new Executor(clients).execute(plan, Map.of())
            .contextWrite(Context.of("caller", "gateway request"))
            .block();

        assertThat(result.getErrors()).isEmpty();
        // The root call and the lookup batches
        assertThat(seen).hasSizeGreaterThan(1).containsOnly("gateway request");
    }

    private static Map<String, SubgraphClient> clients(java.util.function.UnaryOperator<SubgraphClient> wrap) {
        Map<String, SubgraphClient> clients = new LinkedHashMap<>();
        for (String name : BenchmarkSubgraphs.NAMES) {
            clients.put(name, wrap.apply(new SimulatedSubgraphClient(BenchmarkSubgraphs.subgraph(name), 0,
                new BatchingOptions(BatchingOptions.Mode.VARIABLES, BatchingOptions.DEFAULT_MAX_BATCH_SIZE))));
        }
        return clients;
    }

    private static class DelegatingClient implements SubgraphClient {
        private final SubgraphClient delegate;

        DelegatingClient(SubgraphClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public Mono<ExecutionResult> execute(OperationDefinition operation, Map<String, Object> variables) {
            return delegate.execute(operation, variables);
        }

        @Override
        public Mono<List<ExecutionResult>> executeBatch(OperationDefinition operation,
                                                        List<Map<String, Object>> variableSets) {
            return delegate.executeBatch(operation, variableSets);
        }

        @Override
        public BatchingOptions batching() {
            return delegate.batching();
        }
    }
}
