package dev.feddi.federation.engine.executor;

import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.language.OperationDefinition;
import graphql.parser.Parser;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SharedCallSubgraphClientTest {

    private final AtomicInteger calls = new AtomicInteger();

    private final SubgraphClient delegate = (operation, variables) -> {
        calls.incrementAndGet();
        Map<String, Object> product = new HashMap<>(Map.of("upc", String.valueOf(variables.get("upc"))));
        Map<String, Object> data = new HashMap<>();
        data.put("product", product);
        return Mono.just(ExecutionResultImpl.newExecutionResult().data(data).build());
    };

    @Test
    void identicalQueriesShareOneCall() {
        var client = new SharedCallSubgraphClient(delegate);
        // Two separately parsed but identical operations, as two plan steps would have.
        var first = client.execute(operation("query ($upc: ID!) { product(upc: $upc) { name } }"), Map.of("upc", "1"));
        var second = client.execute(operation("query ($upc: ID!) { product(upc: $upc) { name } }"), Map.of("upc", "1"));

        first.block();
        second.block();

        assertThat(calls).hasValue(1);
    }

    @Test
    void differentVariablesAreNotShared() {
        var client = new SharedCallSubgraphClient(delegate);
        var op = operation("query ($upc: ID!) { product(upc: $upc) { name } }");

        client.execute(op, Map.of("upc", "1")).block();
        client.execute(op, Map.of("upc", "2")).block();

        assertThat(calls).hasValue(2);
    }

    @Test
    void mutationsAreNeverShared() {
        var client = new SharedCallSubgraphClient(delegate);
        var op = operation("mutation ($upc: ID!) { product(upc: $upc) { name } }");

        client.execute(op, Map.of("upc", "1")).block();
        client.execute(op, Map.of("upc", "1")).block();

        assertThat(calls).hasValue(2);
    }

    @Test
    void everyConsumerGetsIndependentData() {
        var client = new SharedCallSubgraphClient(delegate);
        var op = operation("query ($upc: ID!) { product(upc: $upc) { name } }");

        List<ExecutionResult> results = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            results.add(client.execute(op, Map.of("upc", "1")).block());
        }
        product(results.get(1)).put("leaked", true);

        assertThat(product(results.get(0))).doesNotContainKey("leaked");
        assertThat(product(results.get(2))).doesNotContainKey("leaked");
        assertThat(calls).hasValue(1);
    }

    private static OperationDefinition operation(String query) {
        return Parser.parse(query).getDefinitionsOfType(OperationDefinition.class).get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> product(ExecutionResult result) {
        return (Map<String, Object>) ((Map<String, Object>) result.getData()).get("product");
    }
}
