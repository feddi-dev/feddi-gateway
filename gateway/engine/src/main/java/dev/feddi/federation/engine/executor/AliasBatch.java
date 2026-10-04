package dev.feddi.federation.engine.executor;

import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.language.AstTransformer;
import graphql.language.Field;
import graphql.language.Node;
import graphql.language.NodeVisitorStub;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import graphql.language.VariableDefinition;
import graphql.language.VariableReference;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;
import graphql.util.TreeTransformerUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Alias batching: rewrites one lookup operation and N variable sets into a single
 * spec-compliant operation with an aliased copy of the root selections per entity, and splits
 * the combined result back into one result per entity.
 *
 * <pre>
 * query ($upc: ID!) { product(upc: $upc) { name } }      with [{upc: 1}, {upc: 2}]
 * becomes
 * query ($upc_b0: ID!, $upc_b1: ID!) {
 *   _b0_product: product(upc: $upc_b0) { name }
 *   _b1_product: product(upc: $upc_b1) { name }
 * }
 * </pre>
 *
 * <p>The number of copies is rounded up to a power of two (capped at the maximum batch size)
 * by repeating the last variable set, so a subgraph sees only a few distinct documents per
 * lookup and its parsed-document cache gets hits.
 */
final class AliasBatch {

    private static final String PREFIX = "_b";

    private final OperationDefinition operation;
    private final Map<String, Object> variables;
    private final List<String> rootKeys;
    private final int size;

    private AliasBatch(OperationDefinition operation, Map<String, Object> variables, List<String> rootKeys, int size) {
        this.operation = operation;
        this.variables = variables;
        this.rootKeys = rootKeys;
        this.size = size;
    }

    /**
     * Returns whether an operation can be alias-batched: all root selections are fields.
     */
    static boolean supports(OperationDefinition operation) {
        return operation.getSelectionSet().getSelections().stream().allMatch(s -> s instanceof Field);
    }

    /**
     * Builds the batched operation for the given variable sets.
     *
     * @param operation    the per-entity lookup operation; see {@link #supports}
     * @param variableSets one variables map per entity, at least one
     * @param maxBatchSize the maximum number of copies (padding never exceeds it)
     */
    static AliasBatch create(OperationDefinition operation, List<Map<String, Object>> variableSets, int maxBatchSize) {
        int size = variableSets.size();
        int copies = Math.max(size, Math.min(bucket(size), maxBatchSize));

        List<Field> rootFields = new ArrayList<>();
        for (Selection<?> selection : operation.getSelectionSet().getSelections()) {
            rootFields.add((Field) selection);
        }
        List<String> rootKeys = rootFields.stream().map(Field::getResultKey).toList();

        List<Selection> selections = new ArrayList<>();
        List<VariableDefinition> definitions = new ArrayList<>();
        Map<String, Object> variables = new LinkedHashMap<>();
        for (int i = 0; i < copies; i++) {
            String suffix = "_b" + i;
            String aliasPrefix = PREFIX + i + "_";
            for (Field field : rootFields) {
                Field renamed = renameVariables(field, suffix);
                selections.add(renamed.transform(b -> b.alias(aliasPrefix + field.getResultKey())));
            }
            for (VariableDefinition definition : operation.getVariableDefinitions()) {
                definitions.add(definition.transform(b -> b.name(definition.getName() + suffix)));
            }
            Map<String, Object> set = variableSets.get(Math.min(i, size - 1));
            set.forEach((name, value) -> variables.put(name + suffix, value));
        }

        OperationDefinition batched = operation.transform(b -> b
            .variableDefinitions(definitions)
            .selectionSet(SelectionSet.newSelectionSet().selections(selections).build()));
        return new AliasBatch(batched, variables, rootKeys, size);
    }

    OperationDefinition operation() {
        return operation;
    }

    Map<String, Object> variables() {
        return variables;
    }

    /**
     * Splits the combined result into one result per entity (padding copies are dropped).
     * Errors whose path starts at an aliased root field go to that entity with the original
     * field name; all other errors go to the first entity.
     */
    List<ExecutionResult> split(ExecutionResult result) {
        Map<String, Object> data = result.getData();
        List<List<GraphQLError>> errorsByEntity = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            errorsByEntity.add(new ArrayList<>());
        }
        for (GraphQLError error : result.getErrors()) {
            assignError(error, errorsByEntity);
        }

        List<ExecutionResult> results = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            ExecutionResultImpl.Builder builder = ExecutionResultImpl.newExecutionResult();
            if (data != null) {
                Map<String, Object> entityData = new LinkedHashMap<>();
                for (String key : rootKeys) {
                    entityData.put(key, data.get(PREFIX + i + "_" + key));
                }
                builder.data(entityData);
            }
            builder.errors(errorsByEntity.get(i));
            results.add(builder.build());
        }
        return results;
    }

    private void assignError(GraphQLError error, List<List<GraphQLError>> errorsByEntity) {
        List<Object> path = error.getPath();
        if (path != null && !path.isEmpty() && path.get(0) instanceof String first && first.startsWith(PREFIX)) {
            int separator = first.indexOf('_', PREFIX.length());
            if (separator > 0) {
                try {
                    int index = Integer.parseInt(first.substring(PREFIX.length(), separator));
                    if (index < errorsByEntity.size()) {
                        List<Object> originalPath = new ArrayList<>(path);
                        originalPath.set(0, first.substring(separator + 1));
                        errorsByEntity.get(index).add(GraphqlErrorBuilder.newError()
                            .message(error.getMessage())
                            .path(originalPath)
                            .locations(error.getLocations())
                            .extensions(error.getExtensions())
                            .build());
                    }
                    // Errors of padding copies are dropped with their data.
                    return;
                } catch (NumberFormatException ignored) {
                    // Not one of our aliases: fall through.
                }
            }
        }
        errorsByEntity.get(0).add(error);
    }

    private static Field renameVariables(Field field, String suffix) {
        Node<?> renamed = new AstTransformer().transform(field, new NodeVisitorStub() {
            @Override
            public TraversalControl visitVariableReference(VariableReference node, TraverserContext<Node> context) {
                return TreeTransformerUtil.changeNode(context, node.transform(b -> b.name(node.getName() + suffix)));
            }
        });
        return (Field) renamed;
    }

    /** Smallest power of two that is at least {@code n}. */
    private static int bucket(int n) {
        return n <= 1 ? 1 : Integer.highestOneBit(n - 1) << 1;
    }
}
