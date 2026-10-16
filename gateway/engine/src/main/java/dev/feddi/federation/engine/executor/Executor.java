package dev.feddi.federation.engine.executor;

import dev.feddi.federation.engine.IntrospectionFields;
import dev.feddi.federation.engine.planner.ExecutionPlan;
import dev.feddi.federation.engine.planner.ExecutionStep;
import dev.feddi.federation.engine.parser.FieldSelectionMap.Alternative;
import dev.feddi.federation.engine.parser.FieldSelectionMap.ListSelection;
import dev.feddi.federation.engine.parser.FieldSelectionMap.ObjectField;
import dev.feddi.federation.engine.parser.FieldSelectionMap.ObjectSelection;
import dev.feddi.federation.engine.parser.FieldSelectionMap.Path;
import dev.feddi.federation.engine.parser.FieldSelectionMap.PathSegment;
import dev.feddi.federation.engine.parser.FieldSelectionMap.SelectedValue;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.GraphQL;
import graphql.GraphQLError;
import graphql.language.AstPrinter;
import graphql.language.Document;
import graphql.language.Field;
import graphql.language.NonNullType;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import graphql.language.VariableDefinition;
import graphql.schema.GraphQLSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Executes a query plan against subgraph clients and merges results.
 *
 * The executor uses true dependency-based parallel execution:
 * 1. Builds a reactive graph where each step waits only for its direct dependencies
 * 2. Steps start executing as soon as all their dependencies complete
 * 3. For repeated execution steps, executes all entity calls in parallel
 * 4. Merges all subgraph responses into a unified result
 *
 * Introspection queries (__schema, __type) targeting the virtual "$introspection"
 * subgraph are handled internally using graphql-java against the supergraph schema.
 */
public final class Executor {

    private static final Logger log = LoggerFactory.getLogger(Executor.class);

    /**
     * The name of the virtual introspection subgraph.
     */
    public static final String INTROSPECTION_SUBGRAPH = "$introspection";

    private final Map<String, SubgraphClient> subgraphClients;
    private final GraphQL introspectionGraphQL;
    private final ExecutionListener listener;

    /**
     * Creates an Executor without introspection support.
     * Introspection queries will fail with "No client for subgraph: $introspection".
     */
    public Executor(Map<String, SubgraphClient> subgraphClients) {
        this(subgraphClients, null, ExecutionListener.NOOP);
    }

    /**
     * Creates an Executor with introspection support.
     *
     * @param subgraphClients the subgraph clients for external subgraphs
     * @param supergraphSchema the supergraph schema for introspection (may be null)
     */
    public Executor(Map<String, SubgraphClient> subgraphClients, GraphQLSchema supergraphSchema) {
        this(subgraphClients, supergraphSchema, ExecutionListener.NOOP);
    }

    /**
     * Creates an Executor with introspection support and an execution listener.
     *
     * @param subgraphClients the subgraph clients for external subgraphs
     * @param supergraphSchema the supergraph schema for introspection (may be null)
     * @param listener listener for execution events (metrics, logging, etc.)
     */
    public Executor(Map<String, SubgraphClient> subgraphClients, GraphQLSchema supergraphSchema,
                    ExecutionListener listener) {
        this.subgraphClients = new LinkedHashMap<>(subgraphClients);
        this.introspectionGraphQL = supergraphSchema != null
            ? GraphQL.newGraphQL(supergraphSchema).build()
            : null;
        this.listener = listener != null ? listener : ExecutionListener.NOOP;
    }

    /**
     * Executes the given plan with the provided variables.
     *
     * @param plan the execution plan to execute
     * @param variables the query variables
     * @return the merged execution result wrapped in a Mono
     */
    public Mono<ExecutionResult> execute(ExecutionPlan plan, Map<String, Object> variables) {
        return executeWithInternalFields(plan, variables)
            .map(result -> withoutArtificialFields(decodeStrings(result), plan));
    }

    /**
     * Executes the plan like {@link #execute}, but leaves the fields the planner added for its own use (keys,
     * requirements, type names) in the data, for a writer that only writes the selected fields anyway, such as the
     * {@link ResponseWriter}.
     *
     * @param plan the execution plan to execute
     * @param variables the query variables
     * @return the merged execution result wrapped in a Mono
     */
    public Mono<ExecutionResult> executeWithInternalFields(ExecutionPlan plan, Map<String, Object> variables) {
        if (plan.isEmpty()) {
            return Mono.just(ExecutionResultImpl.newExecutionResult()
                .data(Map.of())
                .build());
        }

        // Build reactive dependency graph - each step starts as soon as its dependencies complete
        Map<Integer, Mono<StepResult>> stepMonos = buildReactiveGraph(plan.steps(), variables);

        // Collect all step results and merge them
        List<Mono<StepResult>> allSteps = new ArrayList<>(stepMonos.values());

        // The context write caches the subscriber context for the steps: Reactor reads it per subgraph call and per
        // cached step, and each read walks up the operator chain to the nearest context write
        return Mono.zip(allSteps, this::mergeAllStepResults)
            .map(this::buildResult)
            .contextWrite(context -> context);
    }

    /**
     * Builds a reactive dependency graph where each step's Mono waits only for its direct dependencies.
     * Steps are processed in topological order to ensure dependencies are defined before dependents.
     * Each step's Mono is cached to ensure it executes exactly once.
     */
    private Map<Integer, Mono<StepResult>> buildReactiveGraph(List<ExecutionStep> steps,
                                                               Map<String, Object> queryVariables) {
        // Sort topologically to ensure dependencies are processed first
        List<ExecutionStep> sorted = topologicalSort(steps);

        // Thread-safe context for collecting results from completed steps
        ExecutionContext sharedContext = new ExecutionContext();

        // Per-execution clients that share identical query calls between steps
        Map<String, SubgraphClient> clients = new HashMap<>();
        subgraphClients.forEach((name, client) ->
            clients.put(name, new SharedCallSubgraphClient(new ListeningSubgraphClient(client, name, listener))));

        Map<Integer, Mono<StepResult>> stepMonos = new LinkedHashMap<>();

        for (ExecutionStep step : sorted) {
            Mono<StepResult> stepMono;

            if (step.isRoot()) {
                // Root step: execute immediately with query variables
                stepMono = executeStepWithErrorRecovery(step, executeRootStep(step, queryVariables, clients))
                    .doOnNext(sharedContext::merge);
            } else {
                // Dependent step: wait for direct dependencies only, then execute
                List<Mono<StepResult>> dependencies = step.dependsOn().stream()
                    .map(stepMonos::get)
                    .toList();

                // Dependencies complete only after their results have entered the shared
                // response tree. Absolute entity paths must start at that tree's root.
                stepMono = Mono.zip(dependencies, results -> sharedContext)
                    .flatMap(ctx -> executeStepWithErrorRecovery(step, executeStepWithContext(step, ctx, queryVariables, clients)))
                    .doOnNext(sharedContext::merge);
            }

            // Cache ensures each step executes exactly once, even if multiple steps depend on it
            stepMonos.put(step.id(), stepMono.cache());
        }

        return stepMonos;
    }

    /**
     * Executes a step with a pre-built context from its dependencies.
     */
    private Mono<StepResult> executeStepWithContext(ExecutionStep step, ExecutionContext ctx,
                                                     Map<String, Object> queryVariables,
                                                     Map<String, SubgraphClient> clients) {
        // Introspection steps should always be root steps, but handle it just in case
        if (INTROSPECTION_SUBGRAPH.equals(step.subgraph())) {
            return executeIntrospectionStep(step, Map.of());
        }

        SubgraphClient client = clients.get(step.subgraph());
        if (client == null) {
            return Mono.error(new ExecutionException("No client for subgraph: " + step.subgraph()));
        }

        // Dependent steps are entity lookups, which run once per entity, or root steps that wait for others: a
        // root field with @require arguments, which reads them from the response root, or a group of a mutation's
        // top-level fields, which runs after the group before it
        if (step.repeatedExecution()) {
            return executeRepeatedStep(step, client, ctx, queryVariables);
        }
        if (step.entityPath() != null) {
            throw new ExecutionException("Lookup step " + step.id() + " has no requirements to run per entity; "
                + "execution plans must be created by the OperationPlanner");
        }
        Map<String, Object> stepVariables = new LinkedHashMap<>(queryVariables == null ? Map.of() : queryVariables);
        synchronized (ctx) {
            stepVariables.putAll(new RequirementReader(step.possibleTypes())
                .extractVariables(step.requirements(), ctx.getMergedData()));
        }
        if (lacksNonNullVariable(step.operation(), stepVariables)) {
            return Mono.just(nullRootFields(step.operation()));
        }
        return executeRootStep(step, stepVariables, clients);
    }

    /**
     * Wraps step execution with error recovery for partial failure handling.
     * When a step fails (e.g., due to timeout), returns a StepResult with the error
     * instead of failing the entire execution.
     */
    private Mono<StepResult> executeStepWithErrorRecovery(ExecutionStep step, Mono<StepResult> execution) {
        return execution.onErrorResume(e -> {
            log.error("Step {} to subgraph '{}' failed: {}", step.id(), step.subgraph(), e.getMessage(), e);
            StepResult errorResult = new StepResult();
            errorResult.addError(subgraphError(step.subgraph(), e));
            return Mono.just(errorResult);
        });
    }

    /** The GraphQL error for a failed subgraph request; a timeout is also reported to the listener. */
    private GraphQLError subgraphError(String subgraph, Throwable e) {
        if (e instanceof SubgraphTimeoutException timeout) {
            log.warn("Subgraph '{}' timed out", timeout.subgraphName());
            listener.onSubgraphTimeout(timeout.subgraphName());
            return new SubgraphTimeoutError(timeout.subgraphName());
        }
        log.warn("Subgraph '{}' execution error: {}", subgraph, e.getMessage());
        return new SubgraphExecutionError(subgraph, e.getMessage());
    }

    /**
     * Merges all step results into a final execution context.
     */
    private ExecutionContext mergeAllStepResults(Object[] results) {
        ExecutionContext ctx = new ExecutionContext();
        for (Object result : results) {
            ctx.merge((StepResult) result);
        }
        return ctx;
    }

    /**
     * Sorts steps topologically based on dependencies.
     * Steps with no dependencies come first.
     */
    private List<ExecutionStep> topologicalSort(List<ExecutionStep> steps) {
        Map<Integer, ExecutionStep> stepMap = new HashMap<>();
        for (ExecutionStep step : steps) {
            stepMap.put(step.id(), step);
        }

        Map<Integer, Integer> inDegree = new HashMap<>();
        for (ExecutionStep step : steps) {
            inDegree.put(step.id(), step.dependsOn().size());
        }

        List<ExecutionStep> sorted = new ArrayList<>();
        List<Integer> queue = new ArrayList<>();

        for (ExecutionStep step : steps) {
            if (step.isRoot()) {
                queue.add(step.id());
            }
        }

        while (!queue.isEmpty()) {
            int stepId = queue.remove(0);
            sorted.add(stepMap.get(stepId));

            for (ExecutionStep step : steps) {
                if (step.dependsOn().contains(stepId)) {
                    int newDegree = inDegree.get(step.id()) - 1;
                    inDegree.put(step.id(), newDegree);
                    if (newDegree == 0) {
                        queue.add(step.id());
                    }
                }
            }
        }

        if (sorted.size() != steps.size()) {
            throw new ExecutionException("Circular dependency detected in execution plan");
        }

        return sorted;
    }

    /**
     * Executes a root step with the query variables.
     */
    private Mono<StepResult> executeRootStep(ExecutionStep step, Map<String, Object> variables,
                                             Map<String, SubgraphClient> clients) {
        // Handle introspection subgraph internally
        if (INTROSPECTION_SUBGRAPH.equals(step.subgraph())) {
            return executeIntrospectionStep(step, variables);
        }

        SubgraphClient client = clients.get(step.subgraph());
        if (client == null) {
            return Mono.error(new ExecutionException("No client for subgraph: " + step.subgraph()));
        }

        // Filter variables to only those defined in the step's operation
        Map<String, Object> filteredVariables = filterVariablesForOperation(step.operation(), variables);
        return client.execute(step.operation(), filteredVariables)
            .map(result -> {
                StepResult stepResult = new StepResult();
                if (result.getData() != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = (Map<String, Object>) result.getData();
                    stepResult.setRootData(data);
                    log.debug("Step {} received data from subgraph '{}'", step.id(), step.subgraph());
                }
                // Capture any GraphQL errors from the subgraph response
                if (result.getErrors() != null && !result.getErrors().isEmpty()) {
                    log.info("Step {} received {} error(s) from subgraph '{}'",
                        step.id(), result.getErrors().size(), step.subgraph());
                    for (graphql.GraphQLError error : result.getErrors()) {
                        log.info("Subgraph '{}' error: {} (type: {})",
                            step.subgraph(), error.getMessage(), error.getErrorType());
                        stepResult.addError(error);
                    }
                }
                return stepResult;
            });
    }

    /**
     * Executes an introspection step against the supergraph schema.
     */
    private Mono<StepResult> executeIntrospectionStep(ExecutionStep step, Map<String, Object> variables) {
        if (introspectionGraphQL == null) {
            return Mono.error(new ExecutionException(
                "Introspection not supported: no supergraph schema provided to executor"));
        }

        // Convert operation to query string
        OperationDefinition operation = step.operation();
        Document document = Document.newDocument()
            .definition(operation)
            .build();
        String queryString = AstPrinter.printAst(document);

        ExecutionInput input = ExecutionInput.newExecutionInput()
            .query(queryString)
            .variables(variables != null ? variables : Map.of())
            .build();

        return Mono.fromFuture(introspectionGraphQL.executeAsync(input))
            .map(result -> {
                StepResult stepResult = new StepResult();
                if (result.getData() != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = (Map<String, Object>) result.getData();
                    stepResult.setRootData(data);
                }
                return stepResult;
            });
    }

    /**
     * Executes a repeated step for each parent entity in parallel.
     */
    private Mono<StepResult> executeRepeatedStep(ExecutionStep step, SubgraphClient client,
                                                 ExecutionContext ctx, Map<String, Object> queryVariables) {
        List<Map<String, Object>> parentContexts = selectParentContexts(step, ctx);

        if (parentContexts == null || parentContexts.isEmpty()) {
            return Mono.just(new StepResult());
        }

        // Get field names that should be set to null for skipped entities
        final Set<String> nullFieldNames = getFirstLevelFieldNames(step.operation());
        final List<String> wrapperPath = lookupWrapperPath(step.operation());
        final Map<String, Object> operationVariables = filterVariablesForOperation(step.operation(), queryVariables);
        final RequirementReader reader = new RequirementReader(step.possibleTypes());
        final String[] requiredVariables = requiredVariables(step.operation());

        // Group entities by the variables their lookup needs: the same entity often appears
        // at many positions (e.g. one product under many reviews), but is fetched only once.
        Map<Map<String, Object>, UniqueEntity> uniqueEntities = new LinkedHashMap<>();
        List<EntityResult> skipped = new ArrayList<>();
        for (Map<String, Object> context : parentContexts) {
            // Start with filtered query variables, then overlay requirement variables
            // Requirement variables take precedence since they're entity-specific
            Map<String, Object> stepVariables = new CompactMap(operationVariables.size() + step.requirements().size());
            stepVariables.putAll(operationVariables);
            reader.putVariables(step.requirements(), context, stepVariables);
            if (lacksAny(requiredVariables, stepVariables)) {
                skipped.add(new EntityResult(context, null));
                continue;
            }
            uniqueEntities.computeIfAbsent(stepVariables, UniqueEntity::new).positions().add(context);
        }

        // Fetch the unique entities: one request each, or batched as configured for the subgraph. Most steps
        // fit into one batch, which needs no Flux.
        List<List<UniqueEntity>> batches = batches(List.copyOf(uniqueEntities.values()), step.operation(),
            client.batching());
        Mono<List<EntityResult>> fetched = batches.size() == 1
            ? fetchBatchOrFail(step, client, batches.get(0))
            : Flux.fromIterable(batches)
                .flatMap(batch -> fetchBatchOrFail(step, client, batch))
                .collectList()
                .map(lists -> {
                    List<EntityResult> all = new ArrayList<>();
                    lists.forEach(all::addAll);
                    return all;
                });
        return fetched.map(entityResults -> {
            StepResult stepResult = new StepResult();
            for (EntityResult entityResult : entityResults) {
                mergeEntityResult(entityResult, step, ctx, wrapperPath, nullFieldNames, stepResult);
            }
            for (EntityResult entityResult : skipped) {
                mergeEntityResult(entityResult, step, ctx, wrapperPath, nullFieldNames, stepResult);
            }
            return stepResult;
        });
    }

    /**
     * Fetches a batch; a failure does not fail the step: it is reported once, and every position of the batch's
     * entities gets null fields.
     */
    private static Mono<List<EntityResult>> fetchBatchOrFail(ExecutionStep step, SubgraphClient client,
                                                             List<UniqueEntity> batch) {
        return fetchBatch(step.operation(), client, batch)
            .onErrorResume(e -> {
                List<EntityResult> failed = new ArrayList<>();
                for (UniqueEntity entity : batch) {
                    for (Map<String, Object> context : entity.positions()) {
                        failed.add(new EntityResult(context, null, failed.isEmpty() ? e : null));
                    }
                }
                return Mono.just(failed);
            });
    }

    /**
     * Merges one entity's lookup result into its position in the response: the lookup's data, or null for the
     * step's fields when the entity was skipped, its lookup returned null (e.g. {@code {bookByUpc: null}}) or its
     * request failed. The subgraph's errors and the failure go into {@code stepResult}.
     */
    private void mergeEntityResult(EntityResult entityResult, ExecutionStep step, ExecutionContext ctx,
                                   List<String> wrapperPath, Set<String> nullFieldNames, StepResult stepResult) {
        ExecutionResult result = entityResult.result();
        if (result != null && result.getErrors() != null) {
            result.getErrors().forEach(stepResult::addError);
        }
        if (entityResult.error() != null) {
            stepResult.addError(subgraphError(step.subgraph(), entityResult.error()));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> data = result == null || result.getData() == null ? null
            : unwrapLookup((Map<String, Object>) result.getData(), wrapperPath);
        // One lock for all merges (root and lookup results are merged deeply, so they can write to the same
        // nested objects)
        synchronized (ctx) {
            if (data == null || hasNullValue(data)) {
                nullFieldNames.forEach(fieldName -> entityResult.context().put(fieldName, null));
            } else {
                mergeIntoContext(entityResult.context(), data);
            }
        }
    }

    private static boolean hasNullValue(Map<String, Object> data) {
        for (Object value : data.values()) {
            if (value == null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a variable that the operation declares non-null (without default) has no value, e.g. a key or a
     * {@code @require} value that resolved to null. Such a request is invalid, so it is not sent and the fields it
     * would fetch become null, as in ChilliCream's Fusion federation gateway; the null propagation of the response
     * reports non-null fields among them. Nullable variables are sent with null.
     */
    private static boolean lacksNonNullVariable(OperationDefinition operation, Map<String, Object> variables) {
        return lacksAny(requiredVariables(operation), variables);
    }

    /** The variables an operation declares non-null without a default value (interned, as map keys are). */
    private static String[] requiredVariables(OperationDefinition operation) {
        return operation.getVariableDefinitions().stream()
            .filter(definition -> definition.getType() instanceof NonNullType && definition.getDefaultValue() == null)
            .map(definition -> definition.getName().intern())
            .toArray(String[]::new);
    }

    private static boolean lacksAny(String[] names, Map<String, Object> variables) {
        for (String name : names) {
            if (variables.get(name) == null) {
                return true;
            }
        }
        return false;
    }

    /**
     * The result of a root step that is not sent: null for each of its top-level fields.
     */
    private static StepResult nullRootFields(OperationDefinition operation) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (Selection<?> selection : operation.getSelectionSet().getSelections()) {
            if (selection instanceof Field field) {
                data.put(field.getResultKey(), null);
            }
        }
        StepResult stepResult = new StepResult();
        stepResult.setRootData(data);
        return stepResult;
    }

    /**
     * Builds the ExecutionResult from the execution context.
     */
    private ExecutionResult buildResult(ExecutionContext ctx) {
        ExecutionResultImpl.Builder builder = ExecutionResultImpl.newExecutionResult()
            .data(ctx.getMergedData());
        for (GraphQLError error : ctx.getErrors()) {
            builder.addError(error);
        }
        return builder.build();
    }

    /**
     * A result whose data has {@link RawString}s (as parsed from subgraph responses) with {@code String}s instead,
     * for consumers of the data as maps.
     */
    public static ExecutionResult decodeStrings(ExecutionResult result) {
        Object data = result.getData();
        Object decoded = RawString.decodeAll(data);
        return decoded == data ? result
            : ExecutionResultImpl.newExecutionResult().from(result).data(decoded).build();
    }

    /**
     * Removes the fields the planner added for its own use from a result of {@link #executeWithInternalFields}:
     * those that are artificial and not requested, for the object's type (paths are "parent.field" or
     * "parent<Type>.field").
     */
    public static ExecutionResult withoutArtificialFields(ExecutionResult result, ExecutionPlan plan) {
        Set<String> artificial = plan.allArtificialFieldPaths();
        if (artificial.isEmpty() || !(result.getData() instanceof Map<?, ?> data)) {
            return result;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> mergedData = (Map<String, Object>) data;
        return ExecutionResultImpl.newExecutionResult()
            .from(result)
            .data(filterArtificialFields(mergedData, artificial, plan.allRequestedFieldPaths(), ""))
            .build();
    }

    /**
     * Recursively filters artificial fields from the response data.
     *
     * @param data the data map to filter
     * @param artificialPaths set of dot-notation paths that are artificial
     * @param currentPath the current path prefix for nested fields
     * @return a new map with artificial fields removed
     */
    private static Map<String, Object> filterArtificialFields(Map<String, Object> data, Set<String> artificial,
                                                        Set<String> requested, String currentPath) {
        String typename = data.get(IntrospectionFields.TYPENAME) instanceof String name ? name : null;
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            String fieldPath = currentPath.isEmpty() ? key : currentPath + "." + key;
            String typedPath = typename == null ? null
                : (currentPath.isEmpty() ? "" : currentPath) + "<" + typename + ">." + key;
            boolean isArtificial = artificial.contains(fieldPath) || (typedPath != null && artificial.contains(typedPath));
            boolean isRequested = requested.contains(fieldPath) || (typedPath != null && requested.contains(typedPath));
            if (isArtificial && !isRequested) {
                continue;
            }
            if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> mapValue = (Map<String, Object>) value;
                result.put(key, filterArtificialFields(mapValue, artificial, requested, fieldPath));
            } else if (value instanceof List<?> list) {
                result.put(key, filterArtificialFieldsInList(list, artificial, requested, fieldPath));
            } else {
                result.put(key, value);
            }
        }
        return result;
    }

    /**
     * Filters artificial fields from a list of values.
     * Each item in the list uses the same path prefix for filtering.
     */
    private static List<?> filterArtificialFieldsInList(List<?> list, Set<String> artificial, Set<String> requested,
                                                 String currentPath) {
        return list.stream()
            .map(item -> {
                if (item instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> mapItem = (Map<String, Object>) item;
                    return filterArtificialFields(mapItem, artificial, requested, currentPath);
                }
                if (item instanceof List<?> nested) {
                    return filterArtificialFieldsInList(nested, artificial, requested, currentPath);
                }
                return item;
            })
            .toList();
    }

    /**
     * Filters query variables to only include those defined in the step's operation.
     * This ensures subgraphs only receive the variables they expect.
     */
    private Map<String, Object> filterVariablesForOperation(OperationDefinition operation, Map<String, Object> variables) {
        if (variables == null || variables.isEmpty()) {
            return Map.of();
        }

        Set<String> definedVars = new HashSet<>();
        for (var varDef : operation.getVariableDefinitions()) {
            definedVars.add(varDef.getName());
        }

        if (definedVars.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> filtered = new LinkedHashMap<>();
        for (String varName : definedVars) {
            if (variables.containsKey(varName)) {
                filtered.put(varName, variables.get(varName));
            }
        }
        return filtered;
    }

    /**
     * Extracts a value from a SelectedValue, trying alternatives in order.
     * Package-private for testing.
     */
    Object extractFromSelectedValue(Map<String, Object> context, SelectedValue selectedValue) {
        return new RequirementReader(Map.of()).extractFromSelectedValue(context, selectedValue);
    }

    /**
     * Extracts a value from a context following a path.
     * Package-private for testing.
     */
    Object extractValueFromPath(Map<String, Object> context, Path path) {
        return new RequirementReader(Map.of()).extractValueFromPath(context, path);
    }

    /**
     * Selects the concrete response objects that should drive a repeated lookup: the
     * objects at the step's entity path. An absent target must not fall back to unrelated
     * objects that happen to have the same key fields (see #49).
     */
    private List<Map<String, Object>> selectParentContexts(ExecutionStep step, ExecutionContext ctx) {
        if (step.entityPath() == null) {
            throw new ExecutionException("Repeated step " + step.id() + " has no entity path; "
                + "execution plans must be created by the OperationPlanner");
        }
        List<Map<String, Object>> targets = new ArrayList<>();
        // The types required along the path, by depth (looked up once per object)
        @SuppressWarnings("unchecked")
        Set<String>[] pathTypes = new Set[step.entityPath().size() + 1];
        step.entityPathTypes().forEach((depth, types) -> {
            if (depth < pathTypes.length) {
                pathTypes[depth] = types;
            }
        });
        synchronized (ctx) {
            collectEntityTargets(ctx.getMergedData(), step.entityPath(), pathTypes, 0, targets);
        }
        if (!step.entityTypes().isEmpty()) {
            // A lookup for one concrete type (e.g. below "... on Book") skips entities of other types.
            targets.removeIf(target -> target.get(IntrospectionFields.TYPENAME) instanceof String typename
                && !step.entityTypes().contains(typename));
        }
        return new RequirementReader(step.possibleTypes()).filterContextsForRequirements(targets, step.requirements());
    }

    /**
     * Follow response keys, traversing lists without consuming a path segment, and skipping objects whose
     * {@code __typename} is not one of the types required at their position.
     */
    @SuppressWarnings("unchecked")
    private void collectEntityTargets(Object value, List<String> path, Set<String>[] pathTypes,
                                      int index, List<Map<String, Object>> targets) {
        if (value instanceof List<?> list) {
            for (Object item : list) {
                collectEntityTargets(item, path, pathTypes, index, targets);
            }
        } else if (value instanceof Map<?, ?> map) {
            Set<String> types = pathTypes[index];
            if (types != null && map.get(IntrospectionFields.TYPENAME) instanceof String typename
                && !types.contains(typename)) {
                return;
            }
            if (index == path.size()) {
                targets.add((Map<String, Object>) map);
            } else {
                collectEntityTargets(map.get(path.get(index)), path, pathTypes, index + 1, targets);
            }
        }
    }

    /**
     * Merges data from a lookup result into a context.
     */
    private void mergeIntoContext(Map<String, Object> context, Map<String, Object> lookupData) {
        for (Object value : lookupData.values()) {
            if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> innerData = (Map<String, Object>) value;
                deepMerge(context, innerData);
            }
        }
    }

    /**
     * Merges {@code source} into {@code target} in place: objects that several steps return for the same response
     * path (e.g. {@code category} with {@code details} from one subgraph and {@code id} from another) are combined
     * field by field, also inside lists of the same length. Nested objects are updated in place, because later
     * steps may already hold them as lookup targets. A null does not replace a value another step returned.
     */
    @SuppressWarnings("unchecked")
    static void deepMerge(Map<String, Object> target, Map<String, Object> source) {
        if (target instanceof CompactMap compactTarget) {
            source.forEach((key, incoming) -> deepMergeField(compactTarget, key, incoming));
            return;
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object existing = target.get(entry.getKey());
            Object incoming = entry.getValue();
            if (existing instanceof Map<?, ?> existingMap && incoming instanceof Map<?, ?> incomingMap) {
                deepMerge((Map<String, Object>) existingMap, (Map<String, Object>) incomingMap);
            } else if (existing instanceof List<?> existingList && incoming instanceof List<?> incomingList
                && existingList.size() == incomingList.size()) {
                for (int i = 0; i < existingList.size(); i++) {
                    if (existingList.get(i) instanceof Map<?, ?> existingItem
                        && incomingList.get(i) instanceof Map<?, ?> incomingItem) {
                        deepMerge((Map<String, Object>) existingItem, (Map<String, Object>) incomingItem);
                    }
                }
            } else if (incoming != null || !target.containsKey(entry.getKey())) {
                target.put(entry.getKey(), incoming);
            }
        }
    }

    /** {@link #deepMerge} of one field into a compact map, with one lookup of the key. */
    @SuppressWarnings("unchecked")
    private static void deepMergeField(CompactMap target, String key, Object incoming) {
        int index = target.indexOf(key, 0);
        if (index < 0) {
            target.append(key, incoming);
            return;
        }
        Object existing = target.valueAt(index);
        if (existing instanceof Map<?, ?> existingMap && incoming instanceof Map<?, ?> incomingMap) {
            deepMerge((Map<String, Object>) existingMap, (Map<String, Object>) incomingMap);
        } else if (existing instanceof List<?> existingList && incoming instanceof List<?> incomingList
            && existingList.size() == incomingList.size()) {
            for (int i = 0; i < existingList.size(); i++) {
                if (existingList.get(i) instanceof Map<?, ?> existingItem
                    && incomingList.get(i) instanceof Map<?, ?> incomingItem) {
                    deepMerge((Map<String, Object>) existingItem, (Map<String, Object>) incomingItem);
                }
            }
        } else if (incoming != null) {
            target.setValueAt(index, incoming);
        }
    }

    /**
     * The argument-less fields that lead from a lookup operation's root to its lookup field, as for a lookup nested
     * below Query ({@code lookups { brandById(id: $id) { ... } }} gives ["lookups"]); empty for a lookup on Query.
     */
    static List<String> lookupWrapperPath(OperationDefinition operation) {
        List<String> path = new ArrayList<>();
        SelectionSet level = operation.getSelectionSet();
        while (level != null && level.getSelections().size() == 1 && level.getSelections().get(0) instanceof Field field
            && field.getArguments().isEmpty() && field.getSelectionSet() != null) {
            path.add(field.getResultKey());
            level = field.getSelectionSet();
        }
        return path;
    }

    /**
     * The lookup field's result below a nested lookup's wrapper fields, as {lookupField: result}; a null on the way
     * counts as a null lookup result.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> unwrapLookup(Map<String, Object> data, List<String> wrapperPath) {
        Map<String, Object> current = data;
        for (String key : wrapperPath) {
            if (!(current.get(key) instanceof Map<?, ?> next)) {
                Map<String, Object> nullResult = new HashMap<>();
                nullResult.put(key, null);
                return nullResult;
            }
            current = (Map<String, Object>) next;
        }
        return current;
    }

    /**
     * Extracts the first-level field names from a lookup operation.
     * For an operation like "query { bookById { author { name } } }",
     * this returns ["author"] - the fields directly under the lookup root.
     * These are the fields that should be set to null when a lookup is skipped.
     */
    private Set<String> getFirstLevelFieldNames(OperationDefinition operation) {
        Set<String> fieldNames = new HashSet<>();
        if (operation.getSelectionSet() == null) {
            return fieldNames;
        }
        SelectionSet lookupLevel = operation.getSelectionSet();
        for (int i = 0; i < lookupWrapperPath(operation).size(); i++) {
            lookupLevel = ((Field) lookupLevel.getSelections().get(0)).getSelectionSet();
        }
        // The first selection is typically the lookup field (e.g., bookById)
        for (Selection<?> selection : lookupLevel.getSelections()) {
            if (selection instanceof Field rootField && rootField.getSelectionSet() != null) {
                // Get the fields under the root (e.g., author under bookById)
                for (Selection<?> innerSelection : rootField.getSelectionSet().getSelections()) {
                    if (innerSelection instanceof Field innerField) {
                        fieldNames.add(innerField.getName());
                    }
                }
            }
        }
        return fieldNames;
    }

    /**
     * Holds result from executing a single step.
     */
    private static class StepResult {
        private Map<String, Object> rootData;
        private final List<GraphQLError> errors = new ArrayList<>();


        Map<String, Object> getRootData() { return rootData; }
        List<GraphQLError> getErrors() { return errors; }

        void setRootData(Map<String, Object> data) { this.rootData = data; }
        void addError(GraphQLError error) { this.errors.add(error); }
    }

    /**
     * An entity to fetch once: the variables of its lookup and every position where it appears.
     */
    private record UniqueEntity(Map<String, Object> variables, List<Map<String, Object>> positions) {
        UniqueEntity(Map<String, Object> variables) {
            this(variables, new ArrayList<>());
        }
    }

    /**
     * Splits the unique entities of a step into the requests to send: one entity per request
     * without batching (or when the operation can't be alias-batched), otherwise chunks of at
     * most {@link BatchingOptions#maxBatchSize()} entities.
     */
    private static List<List<UniqueEntity>> batches(List<UniqueEntity> entities, OperationDefinition operation,
                                                    BatchingOptions batching) {
        boolean batched = switch (batching.mode()) {
            case NONE -> false;
            case ALIAS -> AliasBatch.supports(operation);
            case VARIABLES -> true;
        };
        int size = batched ? batching.maxBatchSize() : 1;
        List<List<UniqueEntity>> batches = new ArrayList<>();
        for (int start = 0; start < entities.size(); start += size) {
            batches.add(entities.subList(start, Math.min(start + size, entities.size())));
        }
        return batches;
    }

    /**
     * Fetches one batch of unique entities and distributes the results to their positions.
     */
    private static Mono<List<EntityResult>> fetchBatch(OperationDefinition operation, SubgraphClient client,
                                                       List<UniqueEntity> batch) {
        List<Map<String, Object>> variableSets = batch.stream().map(UniqueEntity::variables).toList();
        Mono<List<ExecutionResult>> results;
        if (variableSets.size() == 1) {
            results = client.execute(operation, variableSets.get(0)).map(List::of);
        } else if (client.batching().mode() == BatchingOptions.Mode.VARIABLES) {
            results = client.executeBatch(operation, variableSets);
        } else {
            AliasBatch aliasBatch = AliasBatch.create(operation, variableSets, client.batching().maxBatchSize());
            results = client.execute(aliasBatch.operation(), aliasBatch.variables()).map(aliasBatch::split);
        }
        return results.map(list -> {
            if (list.size() != batch.size()) {
                throw new ExecutionException("Subgraph returned " + list.size() + " results for a batch of "
                    + batch.size());
            }
            int positions = 0;
            for (UniqueEntity entity : batch) {
                positions += entity.positions().size();
            }
            List<EntityResult> entityResults = new ArrayList<>(positions);
            for (int i = 0; i < batch.size(); i++) {
                entityResults.addAll(fanOut(batch.get(i).positions(), list.get(i)));
            }
            return entityResults;
        });
    }

    /**
     * Distributes one subgraph result to every position of a deduplicated entity. Errors are
     * reported once, with the first position.
     *
     * <p>The positions share the result's data: all positions of a repeated step lie on the
     * step's entity path, so every later step merges the same fields into all of them. (Sharing
     * across different paths is not safe; see {@link SharedCallSubgraphClient}, which copies.)
     */
    private static List<EntityResult> fanOut(List<Map<String, Object>> contexts, ExecutionResult result) {
        List<EntityResult> results = new ArrayList<>(contexts.size());
        results.add(new EntityResult(contexts.get(0), result));
        if (contexts.size() > 1) {
            ExecutionResult withoutErrors = ExecutionResultImpl.newExecutionResult().data(result.getData()).build();
            for (int i = 1; i < contexts.size(); i++) {
                results.add(new EntityResult(contexts.get(i), withoutErrors));
            }
        }
        return results;
    }

    /**
     * Result from a single entity execution in a repeated step.
     * @param context the entity context that was used for this execution
     * @param result the execution result, or null if skipped/failed
     * @param error optional error if the execution failed (e.g., timeout)
     */
    private record EntityResult(Map<String, Object> context, ExecutionResult result, Throwable error) {
        EntityResult(Map<String, Object> context, ExecutionResult result) {
            this(context, result, null);
        }
    }

    /**
     * Holds execution state across steps.
     * Uses concurrent collections since steps may complete in parallel.
     */
    private class ExecutionContext {
        // Use regular HashMap for mergedData since it may contain null values from GraphQL responses
        // (e.g., when a field resolver throws an error, the field value is null).
        // ConcurrentHashMap doesn't allow null values, but we protect access with synchronized merge().
        private final Map<String, Object> mergedData = new CompactMap();
        private final List<GraphQLError> errors = new CopyOnWriteArrayList<>();

        ExecutionContext() {}

        synchronized void merge(StepResult result) {
            if (result.getRootData() != null) {
                deepMerge(mergedData, result.getRootData());
            }
            if (result.getErrors() != null && !result.getErrors().isEmpty()) {
                errors.addAll(result.getErrors());
            }
        }

        Map<String, Object> getMergedData() { return mergedData; }
        List<GraphQLError> getErrors() { return errors; }
    }

    /**
     * Exception thrown during execution.
     */
    public static class ExecutionException extends RuntimeException {
        public ExecutionException(String message) {
            super(message);
        }

        public ExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * GraphQL error for general subgraph execution failures.
     */
    private record SubgraphExecutionError(String subgraphName, String errorMessage) implements GraphQLError {
        @Override
        public String getMessage() {
            return "Subgraph '" + subgraphName + "' execution failed: " + errorMessage;
        }

        @Override
        public List<graphql.language.SourceLocation> getLocations() {
            return null;
        }

        @Override
        public graphql.ErrorClassification getErrorType() {
            return graphql.ErrorClassification.errorClassification("SUBGRAPH_ERROR");
        }

        @Override
        public Map<String, Object> getExtensions() {
            return Map.of(
                "code", "SUBGRAPH_ERROR",
                "subgraph", subgraphName
            );
        }
    }
}
