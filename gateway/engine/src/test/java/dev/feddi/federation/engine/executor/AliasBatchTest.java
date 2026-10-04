package dev.feddi.federation.engine.executor;

import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.language.AstPrinter;
import graphql.language.OperationDefinition;
import graphql.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AliasBatchTest {

    private static final OperationDefinition LOOKUP = operation(
        "query ($upc: ID!, $weight: Long!) { productByUpc(upc: $upc) { inStock shippingEstimate(weight: $weight) } }");

    @Test
    void rewritesOneAliasedCopyPerEntityWithRenamedVariables() {
        var batch = AliasBatch.create(LOOKUP, List.of(vars("1", 100), vars("2", 200)), 64);

        assertThat(AstPrinter.printAstCompact(batch.operation())).isEqualTo(
            "query ($upc_b0:ID!,$weight_b0:Long!,$upc_b1:ID!,$weight_b1:Long!){"
                + "_b0_productByUpc:productByUpc(upc:$upc_b0){inStock shippingEstimate(weight:$weight_b0)}"
                + "_b1_productByUpc:productByUpc(upc:$upc_b1){inStock shippingEstimate(weight:$weight_b1)}}");
        assertThat(batch.variables()).containsExactly(
            Map.entry("upc_b0", "1"), Map.entry("weight_b0", 100),
            Map.entry("upc_b1", "2"), Map.entry("weight_b1", 200));
    }

    @Test
    void padsToPowerOfTwoBucketByRepeatingTheLastEntity() {
        var batch = AliasBatch.create(LOOKUP, List.of(vars("1", 1), vars("2", 2), vars("3", 3)), 64);

        assertThat(batch.operation().getSelectionSet().getSelections()).hasSize(4);
        assertThat(batch.variables()).containsEntry("upc_b3", "3");
    }

    @Test
    void paddingNeverExceedsMaxBatchSize() {
        var batch = AliasBatch.create(LOOKUP, List.of(vars("1", 1), vars("2", 2), vars("3", 3)), 3);

        assertThat(batch.operation().getSelectionSet().getSelections()).hasSize(3);
    }

    @Test
    void splitsDataAndErrorsBackPerEntity() {
        var batch = AliasBatch.create(LOOKUP, List.of(vars("1", 1), vars("2", 2), vars("3", 3)), 64);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("_b0_productByUpc", Map.of("inStock", true));
        data.put("_b1_productByUpc", null);
        data.put("_b2_productByUpc", Map.of("inStock", false));
        data.put("_b3_productByUpc", Map.of("inStock", false));
        ExecutionResult combined = ExecutionResultImpl.newExecutionResult()
            .data(data)
            .addError(error("not found", List.of("_b1_productByUpc")))
            .addError(error("estimate failed", List.of("_b2_productByUpc", "shippingEstimate")))
            .addError(error("padding error", List.of("_b3_productByUpc")))
            .addError(error("request warning", null))
            .build();

        List<ExecutionResult> results = batch.split(combined);

        assertThat(results).hasSize(3);
        assertThat((Object) results.get(0).getData()).isEqualTo(Map.of("productByUpc", Map.of("inStock", true)));
        Map<String, Object> second = results.get(1).getData();
        assertThat(second).containsEntry("productByUpc", null);
        assertThat(results.get(0).getErrors()).extracting(GraphQLError::getMessage).containsExactly("request warning");
        assertThat(results.get(1).getErrors()).singleElement()
            .satisfies(e -> assertThat(e.getPath()).containsExactly("productByUpc"));
        assertThat(results.get(2).getErrors()).singleElement()
            .satisfies(e -> assertThat(e.getPath()).containsExactly("productByUpc", "shippingEstimate"));
    }

    @Test
    void splitWithoutDataKeepsErrorsOnFirstEntity() {
        var batch = AliasBatch.create(LOOKUP, List.of(vars("1", 1), vars("2", 2)), 64);
        ExecutionResult combined = ExecutionResultImpl.newExecutionResult()
            .addError(error("validation failed", null)).build();

        List<ExecutionResult> results = batch.split(combined);

        assertThat((Object) results.get(0).getData()).isNull();
        assertThat(results.get(0).getErrors()).hasSize(1);
        assertThat(results.get(1).getErrors()).isEmpty();
    }

    @Test
    void supportsOnlyFieldsAtTheRoot() {
        assertThat(AliasBatch.supports(LOOKUP)).isTrue();
        assertThat(AliasBatch.supports(operation("query { ... on Query { a } }"))).isFalse();
    }

    private static Map<String, Object> vars(String upc, int weight) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("upc", upc);
        vars.put("weight", weight);
        return vars;
    }

    private static GraphQLError error(String message, List<Object> path) {
        var builder = GraphqlErrorBuilder.newError().message(message);
        if (path != null) {
            builder.path(path);
        }
        return builder.build();
    }

    private static OperationDefinition operation(String query) {
        return Parser.parse(query).getDefinitionsOfType(OperationDefinition.class).get(0);
    }
}
