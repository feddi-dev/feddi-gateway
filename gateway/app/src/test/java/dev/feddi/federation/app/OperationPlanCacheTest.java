package dev.feddi.federation.app;

import dev.feddi.federation.app.OperationPlanCache.PreparedOperation;
import dev.feddi.federation.engine.compose.Composer.SubgraphInput;
import dev.feddi.federation.engine.executor.ExecutionListener;
import dev.feddi.federation.engine.planner.ExecutionPlan;
import dev.feddi.federation.extension.DocumentProvider;
import dev.feddi.federation.extension.SubgraphClient;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.GraphqlErrorBuilder;
import graphql.execution.preparsed.PreparsedDocumentEntry;
import graphql.language.Document;
import graphql.language.OperationDefinition;
import graphql.parser.Parser;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class OperationPlanCacheTest {

    private static final String SDL = """
        type Query {
          products: [Product!]!
        }
        type Product {
          id: ID!
          name: String
        }
        """;

    private static final Map<String, Object> PRODUCTS = Map.of(
        "products", List.of(Map.of("id", "1", "name", "Table")));

    // ==================== Cache unit tests ====================

    @Test
    void textKeyIncludesOperationName() {
        var cache = new OperationPlanCache();
        var prepared = prepared("{ a }");

        cache.putByText("{ a }", null, prepared);

        assertThat(cache.getByText("{ a }", null)).isSameAs(prepared);
        assertThat(cache.getByText("{ a }", "Other")).isNull();
        assertThat(cache.getByText("{ b }", null)).isNull();
    }

    @Test
    void documentKeyUsesInstanceIdentity() {
        var cache = new OperationPlanCache();
        Document document = Parser.parse("{ a }");
        AtomicInteger preparations = new AtomicInteger();

        var first = cache.getOrPrepare(document, () -> {
            preparations.incrementAndGet();
            return prepared("{ a }");
        });
        var second = cache.getOrPrepare(document, () -> {
            preparations.incrementAndGet();
            return prepared("{ a }");
        });
        cache.getOrPrepare(Parser.parse("{ a }"), () -> {
            preparations.incrementAndGet();
            return prepared("{ a }");
        });

        assertThat(second).isSameAs(first);
        assertThat(preparations).hasValue(2);
    }

    @Test
    void sizeStaysBounded() {
        var cache = new OperationPlanCache(10);
        for (int i = 0; i < 100; i++) {
            cache.putByText("{ f" + i + " }", null, prepared("{ a }"));
        }
        assertThat(cache.size()).isLessThanOrEqualTo(10);
    }

    // ==================== Gateway integration ====================

    @Test
    void repeatedQueryIsPlannedOnce() {
        var registry = new SimpleMeterRegistry();
        var gateway = gateway(registry, null);

        var first = execute(gateway, "{ products { id name } }");
        var second = execute(gateway, "{ products { id name } }");

        assertThat(first.getErrors()).isEmpty();
        assertThat((Object) second.getData()).isEqualTo(first.getData());
        assertThat(planningCount(registry)).isEqualTo(1);
    }

    @Test
    void differentQueriesArePlannedSeparately() {
        var registry = new SimpleMeterRegistry();
        var gateway = gateway(registry, null);

        execute(gateway, "{ products { id } }");
        execute(gateway, "{ products { name } }");

        assertThat(planningCount(registry)).isEqualTo(2);
    }

    @Test
    void invalidQueryIsNotCached() {
        var registry = new SimpleMeterRegistry();
        var gateway = gateway(registry, null);

        var first = execute(gateway, "{ unknownField }");
        var second = execute(gateway, "{ unknownField }");

        assertThat(first.getErrors()).isNotEmpty();
        assertThat(second.getErrors()).isNotEmpty();
        assertThat(planningCount(registry)).isZero();
    }

    @Test
    void persistedDocumentIsPlannedOnce() {
        var registry = new SimpleMeterRegistry();
        Document persisted = Parser.parse("{ products { id name } }");
        DocumentProvider provider = (input, context) ->
            "persisted".equals(input.getExtensions().get("id"))
                ? Mono.just(new PreparsedDocumentEntry(persisted))
                : Mono.empty();
        var gateway = gateway(registry, provider);

        var first = executePersisted(gateway);
        var second = executePersisted(gateway);

        assertThat(first.getErrors()).isEmpty();
        assertThat((Object) second.getData()).isEqualTo(first.getData());
        assertThat(planningCount(registry)).isEqualTo(1);
    }

    @Test
    void providerErrorsAreStillReturned() {
        DocumentProvider provider = (input, context) -> Mono.just(new PreparsedDocumentEntry(
            List.of(GraphqlErrorBuilder.newError().message("PersistedQueryNotFound").build())));
        var gateway = gateway(new SimpleMeterRegistry(), provider);

        var first = execute(gateway, "{ products { id } }");
        var second = execute(gateway, "{ products { id } }");

        assertThat(first.getErrors()).extracting(e -> e.getMessage()).containsExactly("PersistedQueryNotFound");
        assertThat(second.getErrors()).extracting(e -> e.getMessage()).containsExactly("PersistedQueryNotFound");
    }

    // ==================== Helpers ====================

    private static PreparedOperation prepared(String query) {
        Document document = Parser.parse(query);
        return new PreparedOperation(document, document.getDefinitionsOfType(OperationDefinition.class).get(0),
            new ExecutionPlan(List.of()));
    }

    private static FeddiFederationGateway gateway(SimpleMeterRegistry registry, DocumentProvider provider) {
        PerEntitySubgraphClient client = (operation, variables, context) ->
            Mono.just(ExecutionResultImpl.newExecutionResult().data(PRODUCTS).build());
        return FeddiFederationGateway.create(
            List.of(new SubgraphInput("products", "http://localhost:0/graphql", SDL)),
            Map.of("products", client),
            ExecutionListener.NOOP,
            new FeddiGatewayMetrics(registry),
            provider);
    }

    private static ExecutionResult execute(FeddiFederationGateway gateway, String query) {
        return gateway.execute(ExecutionInput.newExecutionInput().query(query).build())
            .block().executionResult();
    }

    private static ExecutionResult executePersisted(FeddiFederationGateway gateway) {
        return gateway.execute(ExecutionInput.newExecutionInput().query("").extensions(Map.of("id", "persisted")).build())
            .block().executionResult();
    }

    private static long planningCount(SimpleMeterRegistry registry) {
        return registry.get("feddi.gateway.planning.duration").timer().count();
    }
}
