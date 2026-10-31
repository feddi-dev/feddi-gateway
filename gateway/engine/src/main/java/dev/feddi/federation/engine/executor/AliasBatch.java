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
import java.util.concurrent.ConcurrentHashMap;

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
    private static final int MAX_TEMPLATES = 10_000;
    private static final boolean EXACT_COPIES = Boolean.getBoolean("feddi.experiment.exactAliasCopies");

    private record Template(OperationDefinition operation, List<String> rootKeys, List<String> variableNames) {
    }

    /** A lookup of a combined batch: its operation and one variables map per entity. */
    record Part(OperationDefinition operation, List<Map<String, Object>> variableSets) {
    }

    /** Where a part's entities are in the combined operation: its root keys and the index of its first copy. */
    private record Layout(List<String> rootKeys, int size, int offset) {
    }

    private record CombinedKey(List<TemplateKey> parts) {
    }

    private record TemplateKey(OperationDefinition operation, int copies) {
        @Override
        public boolean equals(Object other) {
            return other instanceof TemplateKey key && key.operation == operation && key.copies == copies;
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(operation) + copies;
        }
    }

    private static final Map<TemplateKey, Template> TEMPLATES = new ConcurrentHashMap<>();
    private static final Map<CombinedKey, OperationDefinition> COMBINED = new ConcurrentHashMap<>();

    private final OperationDefinition operation;
    private final Map<String, Object> variables;
    private final List<Layout> layouts;
    /** The number of copies in the operation (with padding). */
    private final int copies;

    private AliasBatch(OperationDefinition operation, Map<String, Object> variables, List<Layout> layouts,
                       int copies) {
        this.operation = operation;
        this.variables = variables;
        this.layouts = layouts;
        this.copies = copies;
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
        // Experiment: -Dfeddi.experiment.exactAliasCopies=true sends one copy per entity (no padding)
        int copies = EXACT_COPIES ? size : Math.max(size, Math.min(bucket(size), maxBatchSize));
        Template template = template(operation, copies);

        Map<String, Object> variables = new LinkedHashMap<>();
        putVariables(template, variableSets, copies, 0, variables);
        return new AliasBatch(template.operation(), variables, List.of(new Layout(template.rootKeys(), size, 0)),
            copies);
    }

    /**
     * Builds one operation for the lookups of several parts (different lookups to the same subgraph): the copies
     * of each part follow those of the parts before it, one copy per entity (no padding: the parts' sizes vary
     * together, so padding would not make the documents recur).
     */
    static AliasBatch combine(List<Part> parts) {
        List<TemplateKey> keys = new ArrayList<>(parts.size());
        List<Template> templates = new ArrayList<>(parts.size());
        for (Part part : parts) {
            keys.add(new TemplateKey(part.operation(), part.variableSets().size()));
            templates.add(template(part.operation(), part.variableSets().size()));
        }
        Map<String, Object> variables = new LinkedHashMap<>();
        List<Layout> layouts = new ArrayList<>(parts.size());
        int offset = 0;
        for (int p = 0; p < parts.size(); p++) {
            List<Map<String, Object>> sets = parts.get(p).variableSets();
            putVariables(templates.get(p), sets, sets.size(), offset, variables);
            layouts.add(new Layout(templates.get(p).rootKeys(), sets.size(), offset));
            offset += sets.size();
        }
        CombinedKey key = new CombinedKey(keys);
        OperationDefinition operation = COMBINED.get(key);
        if (operation == null) {
            operation = buildCombined(parts);
            if (COMBINED.size() >= MAX_TEMPLATES) {
                COMBINED.clear();
            }
            COMBINED.put(key, operation);
        }
        return new AliasBatch(operation, variables, layouts, offset);
    }

    private static void putVariables(Template template, List<Map<String, Object>> variableSets, int copies, int offset,
                                     Map<String, Object> variables) {
        int size = variableSets.size();
        for (int i = 0; i < copies; i++) {
            String suffix = "_b" + (offset + i);
            Map<String, Object> set = variableSets.get(Math.min(i, size - 1));
            for (String name : template.variableNames()) {
                if (set.containsKey(name)) {
                    variables.put(name + suffix, set.get(name));
                }
            }
        }
    }

    /**
     * The batched operation for an operation and number of copies. It depends only on these
     * two (variables are passed separately), so it is built once and cached; plans are cached,
     * so the same operation instances recur.
     */
    private static Template template(OperationDefinition operation, int copies) {
        TemplateKey key = new TemplateKey(operation, copies);
        Template template = TEMPLATES.get(key);
        if (template == null) {
            template = buildTemplate(operation, copies);
            if (TEMPLATES.size() >= MAX_TEMPLATES) {
                TEMPLATES.clear();
            }
            TEMPLATES.put(key, template);
        }
        return template;
    }

    private static Template buildTemplate(OperationDefinition operation, int copies) {
        List<String> rootKeys = operation.getSelectionSet().getSelections().stream()
            .map(selection -> ((Field) selection).getResultKey()).toList();
        List<String> variableNames = operation.getVariableDefinitions().stream()
            .map(VariableDefinition::getName).toList();

        List<Selection> selections = new ArrayList<>();
        List<VariableDefinition> definitions = new ArrayList<>();
        addCopies(operation, copies, 0, selections, definitions);
        OperationDefinition batched = operation.transform(b -> b
            .variableDefinitions(definitions)
            .selectionSet(SelectionSet.newSelectionSet().selections(selections).build()));
        return new Template(batched, rootKeys, variableNames);
    }

    private static OperationDefinition buildCombined(List<Part> parts) {
        List<Selection> selections = new ArrayList<>();
        List<VariableDefinition> definitions = new ArrayList<>();
        int offset = 0;
        for (Part part : parts) {
            addCopies(part.operation(), part.variableSets().size(), offset, selections, definitions);
            offset += part.variableSets().size();
        }
        return parts.get(0).operation().transform(b -> b
            .variableDefinitions(definitions)
            .selectionSet(SelectionSet.newSelectionSet().selections(selections).build()));
    }

    /** Adds the aliased copies {@code offset .. offset + copies - 1} of an operation's root fields. */
    private static void addCopies(OperationDefinition operation, int copies, int offset, List<Selection> selections,
                                  List<VariableDefinition> definitions) {
        for (int i = offset; i < offset + copies; i++) {
            String suffix = "_b" + i;
            String aliasPrefix = PREFIX + i + "_";
            for (Selection<?> selection : operation.getSelectionSet().getSelections()) {
                Field field = (Field) selection;
                Field renamed = renameVariables(field, suffix);
                selections.add(renamed.transform(b -> b.alias(aliasPrefix + field.getResultKey())));
            }
            for (VariableDefinition definition : operation.getVariableDefinitions()) {
                definitions.add(definition.transform(b -> b.name(definition.getName() + suffix)));
            }
        }
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
        return splitParts(result).get(0);
    }

    /** Splits the result of a {@linkplain #combine combined} batch: one list of entity results per part. */
    List<List<ExecutionResult>> splitParts(ExecutionResult result) {
        Map<String, Object> data = result.getData();
        List<List<GraphQLError>> errorsByCopy = new ArrayList<>(copies);
        for (int i = 0; i < copies; i++) {
            errorsByCopy.add(new ArrayList<>());
        }
        for (GraphQLError error : result.getErrors()) {
            assignError(error, errorsByCopy);
        }

        List<List<ExecutionResult>> parts = new ArrayList<>(layouts.size());
        for (Layout layout : layouts) {
            List<ExecutionResult> results = new ArrayList<>(layout.size());
            for (int i = layout.offset(); i < layout.offset() + layout.size(); i++) {
                ExecutionResultImpl.Builder builder = ExecutionResultImpl.newExecutionResult();
                if (data != null) {
                    Map<String, Object> entityData = new LinkedHashMap<>();
                    for (String key : layout.rootKeys()) {
                        entityData.put(key, data.get(PREFIX + i + "_" + key));
                    }
                    builder.data(entityData);
                }
                builder.errors(errorsByCopy.get(i));
                results.add(builder.build());
            }
            parts.add(results);
        }
        return parts;
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
