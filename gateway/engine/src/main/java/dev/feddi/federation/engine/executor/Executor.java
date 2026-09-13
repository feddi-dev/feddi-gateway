package dev.feddi.federation.engine.executor;

import dev.feddi.federation.engine.IntrospectionFields;
import dev.feddi.federation.engine.planner.ExecutionPlan;
import dev.feddi.federation.engine.planner.ExecutionStep;
import dev.feddi.federation.engine.planner.RequireArgumentSkipInfo;
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
import graphql.language.OperationDefinition;
import graphql.language.Selection;
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
        if (plan.isEmpty()) {
            return Mono.just(ExecutionResultImpl.newExecutionResult()
                .data(Map.of())
                .build());
        }

        // Build reactive dependency graph - each step starts as soon as its dependencies complete
        Map<Integer, Mono<StepResult>> stepMonos = buildReactiveGraph(plan.steps(), variables);

        // Collect all step results and merge them
        List<Mono<StepResult>> allSteps = new ArrayList<>(stepMonos.values());

        return Mono.zip(allSteps, this::mergeAllStepResults)
            .map(ctx -> buildResult(ctx, plan));
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

        Map<Integer, Mono<StepResult>> stepMonos = new LinkedHashMap<>();

        for (ExecutionStep step : sorted) {
            Mono<StepResult> stepMono;

            if (step.isRoot()) {
                // Root step: execute immediately with query variables
                stepMono = executeStepWithErrorRecovery(step, executeRootStep(step, queryVariables))
                    .doOnNext(sharedContext::merge);
            } else {
                // Dependent step: wait for direct dependencies only, then execute
                List<Mono<StepResult>> dependencies = step.dependsOn().stream()
                    .map(stepMonos::get)
                    .toList();

                stepMono = Mono.zip(dependencies, results -> {
                        // Merge dependency results into a context for this step
                        ExecutionContext ctx = new ExecutionContext();
                        for (Object result : results) {
                            ctx.merge((StepResult) result);
                        }
                        return ctx;
                    })
                    .flatMap(ctx -> executeStepWithErrorRecovery(step, executeStepWithContext(step, ctx, queryVariables)))
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
                                                     Map<String, Object> queryVariables) {
        // Introspection steps should always be root steps, but handle it just in case
        if (INTROSPECTION_SUBGRAPH.equals(step.subgraph())) {
            return executeIntrospectionStep(step, Map.of());
        }

        // Resolve the subgraph client lazily inside repeated/single execution so that
        // steps which skip every entity (e.g. non-null @require resolved to null) do
        // not fail just because no client was registered for an unused subgraph.
        if (step.repeatedExecution()) {
            return executeRepeatedStep(step, ctx, queryVariables);
        } else {
            return executeSingleDependentStep(step, ctx, queryVariables);
        }
    }

    private SubgraphClient requireClient(ExecutionStep step) {
        SubgraphClient client = subgraphClients.get(step.subgraph());
        if (client == null) {
            throw new ExecutionException("No client for subgraph: " + step.subgraph());
        }
        return client;
    }

    /**
     * Wraps step execution with error recovery for partial failure handling.
     * When a step fails (e.g., due to timeout), returns a StepResult with the error
     * instead of failing the entire execution.
     */
    private Mono<StepResult> executeStepWithErrorRecovery(ExecutionStep step, Mono<StepResult> execution) {
        return execution.onErrorResume(e -> {
            log.error("Step {} to subgraph '{}' failed: {}", step.id(), step.subgraph(), e.getMessage(), e);
            StepResult errorResult = new StepResult(step.id());
            if (e instanceof SubgraphTimeoutException ste) {
                log.warn("Subgraph '{}' timed out", ste.subgraphName());
                listener.onSubgraphTimeout(ste.subgraphName());
                errorResult.addError(new SubgraphTimeoutError(ste.subgraphName()));
            } else {
                log.warn("Subgraph '{}' execution error: {}", step.subgraph(), e.getMessage());
                errorResult.addError(new SubgraphExecutionError(step.subgraph(), e.getMessage()));
            }
            return Mono.just(errorResult);
        });
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
    private Mono<StepResult> executeRootStep(ExecutionStep step, Map<String, Object> variables) {
        // Handle introspection subgraph internally
        if (INTROSPECTION_SUBGRAPH.equals(step.subgraph())) {
            return executeIntrospectionStep(step, variables);
        }

        SubgraphClient client = subgraphClients.get(step.subgraph());
        if (client == null) {
            return Mono.error(new ExecutionException("No client for subgraph: " + step.subgraph()));
        }

        // Filter variables to only those defined in the step's operation
        Map<String, Object> filteredVariables = filterVariablesForOperation(step.operation(), variables);

        long start = System.nanoTime();
        return client.execute(step.operation(), filteredVariables)
            .doOnNext(result -> listener.onSubgraphFetchComplete(step.subgraph(), System.nanoTime() - start, true))
            .doOnError(e -> listener.onSubgraphFetchComplete(step.subgraph(), System.nanoTime() - start, false))
            .map(result -> {
                StepResult stepResult = new StepResult(step.id());
                if (result.getData() != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = (Map<String, Object>) result.getData();
                    stepResult.setRootData(data);
                    stepResult.setDataContexts(extractDataContexts(data));
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
                StepResult stepResult = new StepResult(step.id());
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
    private Mono<StepResult> executeRepeatedStep(ExecutionStep step,
                                                 ExecutionContext ctx, Map<String, Object> queryVariables) {
        int parentStepId = step.dependsOn().get(0);
        List<Map<String, Object>> parentContexts = selectParentContexts(step, ctx, parentStepId);

        if (parentContexts == null || parentContexts.isEmpty()) {
            return Mono.just(new StepResult(step.id()));
        }

        final List<Map<String, Object>> contexts = parentContexts;
        // Get field names that should be set to null for skipped entities
        final Set<String> nullFieldNames = getFirstLevelFieldNames(step.operation());

        // Execute all entity calls in parallel
        return Flux.fromIterable(contexts)
            .flatMap(context -> {
                // Start with filtered query variables, then overlay requirement variables
                // Requirement variables take precedence since they're entity-specific
                Map<String, Object> stepVariables = new LinkedHashMap<>(
                    filterVariablesForOperation(step.operation(), queryVariables));
                Map<String, Object> extractedVars = extractVariables(step.requirements(), context);
                stepVariables.putAll(extractedVars);

                // Skip entities that don't have essential @is key fields (null key handling).
                if (!hasEssentialKeyFields(step.keyRequirements(), context)) {
                    return Mono.just(new EntityResult(context, null));
                }

                // Skip when a non-null @require argument resolved to null (Cases 2/3).
                // Nullable @require args (Case 1) are intentionally allowed through with null.
                List<GraphQLError> requireNullErrors = collectRequiredArgumentNullErrors(step, extractedVars);
                if (requireNullErrors != null) {
                    return Mono.just(new EntityResult(context, null, null, requireNullErrors));
                }

                SubgraphClient client;
                try {
                    client = requireClient(step);
                } catch (ExecutionException e) {
                    return Mono.error(e);
                }

                long entityStart = System.nanoTime();
                return client.execute(step.operation(), stepVariables)
                    .doOnNext(result -> listener.onSubgraphFetchComplete(step.subgraph(), System.nanoTime() - entityStart, true))
                    .doOnError(e -> listener.onSubgraphFetchComplete(step.subgraph(), System.nanoTime() - entityStart, false))
                    .map(result -> new EntityResult(context, result))
                    .onErrorResume(e -> {
                        // Handle individual entity errors without failing the entire step
                        // Capture the error so we can add it to the response
                        return Mono.just(new EntityResult(context, null, e));
                    });
            })
            .collectList()
            .map(entityResults -> {
                StepResult stepResult = new StepResult(step.id());
                List<Map<String, Object>> resultContexts = new ArrayList<>();
                List<Map<String, Object>> allNestedEntities = new ArrayList<>();

                for (EntityResult er : entityResults) {
                    if (er.result() != null && er.result().getData() != null) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> data = (Map<String, Object>) er.result().getData();

                        // Capture any GraphQL errors from the subgraph response
                        if (er.result().getErrors() != null) {
                            for (graphql.GraphQLError error : er.result().getErrors()) {
                                stepResult.addError(error);
                            }
                        }

                        // Check if the lookup returned null (e.g., {bookByUpc: null})
                        boolean lookupReturnedNull = data.values().stream()
                            .anyMatch(v -> v == null);

                        if (lookupReturnedNull) {
                            // Lookup returned null - set the expected fields to null
                            // Synchronize: parallel steps may share the same context map
                            synchronized (er.context()) {
                                for (String fieldName : nullFieldNames) {
                                    er.context().put(fieldName, null);
                                }
                            }
                            // Still add to resultContexts so downstream steps can set their fields to null
                            resultContexts.add(er.context());
                        } else {
                            // Synchronize: parallel steps may share the same context map
                            synchronized (er.context()) {
                                mergeIntoContext(er.context(), data);
                            }
                            resultContexts.add(er.context());

                            List<Map<String, Object>> nested = extractNestedContexts(data);
                            allNestedEntities.addAll(nested);
                        }
                    } else {
                        // For skipped/failed entities (null result), set the expected fields to null
                        // This ensures the response shows null for fields that couldn't be fetched
                        // Synchronize: parallel steps may share the same context map
                        synchronized (er.context()) {
                            for (String fieldName : nullFieldNames) {
                                er.context().put(fieldName, null);
                            }
                        }
                        // Still add to resultContexts so downstream steps can set their fields to null
                        resultContexts.add(er.context());

                        // Case 3: non-null @require + non-null field return → REQUIRED_ARGUMENT_NULL
                        if (er.skipErrors() != null) {
                            for (GraphQLError error : er.skipErrors()) {
                                stepResult.addError(error);
                            }
                        }

                        // If there was an error (e.g., timeout), record it
                        if (er.error() != null) {
                            if (er.error() instanceof SubgraphTimeoutException ste) {
                                listener.onSubgraphTimeout(ste.subgraphName());
                                stepResult.addError(new SubgraphTimeoutError(ste.subgraphName()));
                            } else {
                                stepResult.addError(new SubgraphExecutionError(step.subgraph(), er.error().getMessage()));
                            }
                        }
                    }
                }

                stepResult.setDataContexts(resultContexts);
                if (!allNestedEntities.isEmpty()) {
                    stepResult.setNestedContexts(allNestedEntities);
                }
                return stepResult;
            });
    }

    /**
     * Executes a single dependent step (non-repeated).
     */
    private Mono<StepResult> executeSingleDependentStep(ExecutionStep step,
                                                        ExecutionContext ctx, Map<String, Object> queryVariables) {
        int parentStepId = step.dependsOn().get(0);
        Map<String, Object> parentData = ctx.getStepData(parentStepId);

        if (parentData == null) {
            parentData = Map.of();
        }

        // Start with filtered query variables, then overlay requirement variables
        // Requirement variables take precedence since they're entity-specific
        Map<String, Object> stepVariables = new LinkedHashMap<>(
            filterVariablesForOperation(step.operation(), queryVariables));
        stepVariables.putAll(extractVariables(step.requirements(), parentData));

        SubgraphClient client = requireClient(step);

        long depStart = System.nanoTime();
        return client.execute(step.operation(), stepVariables)
            .doOnNext(result -> listener.onSubgraphFetchComplete(step.subgraph(), System.nanoTime() - depStart, true))
            .doOnError(e -> listener.onSubgraphFetchComplete(step.subgraph(), System.nanoTime() - depStart, false))
            .map(result -> {
                StepResult stepResult = new StepResult(step.id());
                if (result.getData() != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = (Map<String, Object>) result.getData();
                    stepResult.setStepData(data);
                }
                // Capture any GraphQL errors from the subgraph response
                if (result.getErrors() != null) {
                    for (graphql.GraphQLError error : result.getErrors()) {
                        stepResult.addError(error);
                    }
                }
                return stepResult;
            });
    }

    /**
     * Builds the final ExecutionResult from the execution context.
     * Filters out artificial fields that were added for internal purposes.
     */
    private ExecutionResult buildResult(ExecutionContext ctx, ExecutionPlan plan) {
        Map<String, Object> mergedData = ctx.getMergedData();

        // Filter artificial fields from the final response
        // Only filter paths that are artificial AND NOT requested (handles overlap cases)
        Set<String> pathsToFilter = plan.pathsToFilter();
        Map<String, Object> filteredData = filterArtificialFields(mergedData, pathsToFilter, "");

        ExecutionResultImpl.Builder builder = ExecutionResultImpl.newExecutionResult()
            .data(filteredData);

        for (GraphQLError error : ctx.getErrors()) {
            builder.addError(error);
        }

        return builder.build();
    }

    /**
     * Recursively filters artificial fields from the response data.
     *
     * @param data the data map to filter
     * @param artificialPaths set of dot-notation paths that are artificial
     * @param currentPath the current path prefix for nested fields
     * @return a new map with artificial fields removed
     */
    private Map<String, Object> filterArtificialFields(Map<String, Object> data,
                                                        Set<String> artificialPaths,
                                                        String currentPath) {
        if (artificialPaths.isEmpty()) {
            return data;
        }

        Map<String, Object> result = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            String fieldPath = currentPath.isEmpty() ? key : currentPath + "." + key;

            // Skip artificial fields
            if (artificialPaths.contains(fieldPath)) {
                continue;
            }

            // Recursively filter nested structures
            if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> mapValue = (Map<String, Object>) value;
                result.put(key, filterArtificialFields(mapValue, artificialPaths, fieldPath));
            } else if (value instanceof List) {
                result.put(key, filterArtificialFieldsInList((List<?>) value, artificialPaths, fieldPath));
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
    private List<?> filterArtificialFieldsInList(List<?> list, Set<String> artificialPaths, String currentPath) {
        return list.stream()
            .map(item -> {
                if (item instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> mapItem = (Map<String, Object>) item;
                    return filterArtificialFields(mapItem, artificialPaths, currentPath);
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
     * Extracts variables from a data context based on requirement paths.
     * All requirement variables are included in the result - if a path doesn't exist
     * in the context (e.g., a type-specific field on a different concrete type),
     * the variable is set to null.
     */
    private Map<String, Object> extractVariables(Map<String, SelectedValue> requirements, Map<String, Object> context) {
        Map<String, Object> variables = new LinkedHashMap<>();

        for (Map.Entry<String, SelectedValue> entry : requirements.entrySet()) {
            String varName = entry.getKey();
            SelectedValue selectedValue = entry.getValue();

            Object value = extractFromSelectedValue(context, selectedValue);
            // Always include the variable, even if null - this is expected for
            // type-conditional fields (e.g., @require(field: "data.baz") when
            // the concrete type is Qux which doesn't have a baz field)
            variables.put(varName, value);
        }

        return variables;
    }

    /**
     * If any non-null {@code @require} argument extracted to null, returns an (possibly empty)
     * list of Case 3 errors and signals that the subgraph call should be skipped.
     * Returns {@code null} when the call should proceed.
     */
    private List<GraphQLError> collectRequiredArgumentNullErrors(ExecutionStep step,
                                                                  Map<String, Object> extractedVars) {
        if (step.nonNullRequireArguments().isEmpty()) {
            return null;
        }

        boolean shouldSkip = false;
        List<GraphQLError> errors = new ArrayList<>();
        for (var entry : step.nonNullRequireArguments().entrySet()) {
            if (extractedVars.get(entry.getKey()) != null) {
                continue;
            }
            shouldSkip = true;
            RequireArgumentSkipInfo info = entry.getValue();
            if (info.fieldReturnNonNull()) {
                errors.add(new RequiredArgumentNullError(info.fieldName(), entry.getKey()));
            }
        }
        return shouldSkip ? errors : null;
    }

    /**
     * Checks if essential {@code @is} key fields are present and non-null in the context.
     *
     * Only lookup-key requirements should be passed here — {@code @require} arguments are
     * excluded so a null nullable required value still triggers a subgraph call.
     *
     * For single-segment paths (like "id"), the field must exist AND be non-null.
     * This handles the case where a lookup returned null and the executor added
     * the key with null value to the context.
     *
     * For multi-segment paths (like "data.baz"), we only check if the first
     * segment exists, since nested fields can be null due to type conditions
     * (e.g., @require(field: "data.baz") when entity is Qux which has no baz).
     *
     * For alternatives, if ANY alternative provides a valid value, we proceed.
     */
    private boolean hasEssentialKeyFields(Map<String, SelectedValue> requirements, Map<String, Object> context) {
        for (SelectedValue selectedValue : requirements.values()) {
            // For each requirement, check if ANY alternative can provide a value
            boolean hasValidAlternative = false;

            for (Alternative alt : selectedValue.alternatives()) {
                if (alt instanceof Path path) {
                    if (path.segments().size() == 1) {
                        // Single-segment path: must exist AND be non-null
                        // This catches the case where lookup returned null
                        String fieldName = path.segments().get(0).fieldName();
                        if (context.containsKey(fieldName) && context.get(fieldName) != null) {
                            hasValidAlternative = true;
                            break;
                        }
                    } else {
                        // Multi-segment path: only check first segment exists
                        // Nested fields can be null due to type conditions
                        String firstField = path.segments().get(0).fieldName();
                        if (context.containsKey(firstField)) {
                            hasValidAlternative = true;
                            break;
                        }
                    }
                } else {
                    // ObjectSelection or ListSelection - assume valid
                    hasValidAlternative = true;
                    break;
                }
            }

            if (!hasValidAlternative) {
                return false;
            }
        }
        return true;
    }

    /**
     * Extracts a value from a SelectedValue, trying alternatives in order.
     * Package-private for testing.
     */
    Object extractFromSelectedValue(Map<String, Object> context, SelectedValue selectedValue) {
        for (Alternative alt : selectedValue.alternatives()) {
            Object value = extractFromAlternative(context, alt);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * Extracts a value from a single Alternative.
     */
    private Object extractFromAlternative(Map<String, Object> context, Alternative alt) {
        return switch (alt) {
            case Path path -> extractValueFromPath(context, path);
            case ObjectSelection obj -> extractFromObjectSelection(context, obj);
            case ListSelection list -> extractFromListSelection(context, list);
        };
    }

    /**
     * Extracts a value from a context following a path.
     * Handles list traversal by collecting values from all list elements.
     * Package-private for testing.
     */
    Object extractValueFromPath(Map<String, Object> context, Path path) {
        return extractValueFromPathWithIndex(context, path, 0);
    }

    /**
     * Recursive helper for extractValueFromPath that handles list traversal.
     */
    private Object extractValueFromPathWithIndex(Object current, Path path, int segmentIndex) {
        List<PathSegment> segments = path.segments();

        // Check initial type condition at the start of path extraction
        // e.g., for path <Movie>.code, only extract if context is a Movie
        if (segmentIndex == 0 && path.hasInitialTypeCondition() && current instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) current;
            String typename = (String) map.get(IntrospectionFields.TYPENAME);
            if (typename == null || !path.initialTypeCondition().equals(typename)) {
                return null;
            }
        }

        for (int i = segmentIndex; i < segments.size(); i++) {
            PathSegment segment = segments.get(i);

            if (current instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) current;

                // Get the field value first
                current = map.get(segment.fieldName());

                // Check infix type condition on the result
                if (segment.typeCondition() != null) {
                    if (current instanceof Map) {
                        // Single object: check typename directly
                        @SuppressWarnings("unchecked")
                        Map<String, Object> resultMap = (Map<String, Object>) current;
                        String typename = (String) resultMap.get(IntrospectionFields.TYPENAME);
                        if (typename == null || !segment.typeCondition().equals(typename)) {
                            return null;
                        }
                    } else if (current instanceof List) {
                        // List: filter elements by typename, then continue extraction
                        @SuppressWarnings("unchecked")
                        List<Object> list = (List<Object>) current;
                        List<Object> results = new ArrayList<>();
                        for (Object item : list) {
                            if (item instanceof Map) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> itemMap = (Map<String, Object>) item;
                                String typename = (String) itemMap.get(IntrospectionFields.TYPENAME);
                                if (typename != null && segment.typeCondition().equals(typename)) {
                                    // Type matches - continue extraction from this item
                                    Object extracted = extractValueFromPathWithIndex(item, path, i + 1);
                                    if (extracted != null) {
                                        if (extracted instanceof List) {
                                            results.addAll((List<?>) extracted);
                                        } else {
                                            results.add(extracted);
                                        }
                                    }
                                }
                            }
                        }
                        return results.isEmpty() ? null : results;
                    } else {
                        // Type condition on non-object/non-list - can't match
                        return null;
                    }
                }
            } else if (current instanceof List) {
                // When we encounter a list, extract from each element and collect results
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) current;
                List<Object> results = new ArrayList<>();
                for (Object item : list) {
                    Object extracted = extractValueFromPathWithIndex(item, path, i);
                    if (extracted != null) {
                        if (extracted instanceof List) {
                            // Flatten nested lists
                            results.addAll((List<?>) extracted);
                        } else {
                            results.add(extracted);
                        }
                    }
                }
                return results;
            } else {
                return null;
            }
        }

        return current;
    }

    /**
     * Extracts an object from an ObjectSelection pattern.
     */
    private Object extractFromObjectSelection(Map<String, Object> context, ObjectSelection obj) {
        Map<String, Object> source = context;
        if (obj.pathPrefix() != null) {
            Object prefixValue = extractValueFromPath(context, obj.pathPrefix());
            if (prefixValue instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> prefixMap = (Map<String, Object>) prefixValue;
                source = prefixMap;
            } else {
                return null;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        for (ObjectField field : obj.fields()) {
            Object value = extractFromSelectedValue(source, field.value());
            if (value != null) {
                result.put(field.name(), value);
            }
        }

        return result.isEmpty() ? null : result;
    }

    /**
     * Extracts values from a ListSelection pattern.
     */
    private Object extractFromListSelection(Map<String, Object> context, ListSelection list) {
        Object listValue;
        if (list.pathPrefix() != null) {
            listValue = extractValueFromPath(context, list.pathPrefix());
        } else {
            return null;
        }

        if (!(listValue instanceof List)) {
            return null;
        }

        @SuppressWarnings("unchecked")
        List<Object> sourceList = (List<Object>) listValue;

        List<Object> result = new ArrayList<>();
        for (Object item : sourceList) {
            if (item instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> itemMap = (Map<String, Object>) item;
                Object extracted = extractFromSelectedValue(itemMap, list.elementValue());
                if (extracted != null) {
                    result.add(extracted);
                }
            }
        }

        return result;
    }

    /**
     * Extracts data contexts (entities) from a result data map.
     */
    private List<Map<String, Object>> extractDataContexts(Map<String, Object> data) {
        List<Map<String, Object>> contexts = new ArrayList<>();

        for (Object value : data.values()) {
            if (value instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) value;
                for (Object item : list) {
                    if (item instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> itemMap = (Map<String, Object>) item;
                        contexts.add(itemMap);
                    }
                }
            } else if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) value;
                contexts.add(map);
            }
        }

        return contexts;
    }

    /**
     * Selects the concrete response objects that should drive a repeated lookup.
     *
     * Nested contexts are preferred because they represent objects produced below
     * the parent lookup root. If none of them carry the fields required by this
     * lookup, fall back to the direct parent contexts. That fallback is important
     * for multi-hop lookups where a previous lookup merged a translated key into
     * the original entity object, including the null-key propagation path.
     */
    private List<Map<String, Object>> selectParentContexts(ExecutionStep step, ExecutionContext ctx, int parentStepId) {
        List<Map<String, Object>> nestedContexts =
            filterContextsForRequirements(ctx.getNestedContexts(parentStepId), step.requirements());
        if (!nestedContexts.isEmpty()) {
            return nestedContexts;
        }

        List<Map<String, Object>> dataContexts =
            filterContextsForRequirements(ctx.getDataContexts(parentStepId), step.requirements());
        if (!dataContexts.isEmpty()) {
            return dataContexts;
        }

        if (step.requirements().isEmpty()) {
            return List.of();
        }

        return findMatchingContexts(ctx.getMergedData(), step.requirements());
    }

    private List<Map<String, Object>> filterContextsForRequirements(List<Map<String, Object>> contexts,
                                                                     Map<String, SelectedValue> requirements) {
        if (contexts == null || contexts.isEmpty()) {
            return List.of();
        }
        if (requirements.isEmpty()) {
            return contexts;
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> context : contexts) {
            if (contextCanProvideRequirements(context, requirements)) {
                result.add(context);
            }
        }
        return result;
    }

    /**
     * Checks whether a context has the source fields for every requirement.
     * Null values count as present so downstream steps can still write nulls for
     * fields that cannot be resolved after a null lookup.
     */
    private boolean contextCanProvideRequirements(Map<String, Object> context,
                                                   Map<String, SelectedValue> requirements) {
        for (SelectedValue selectedValue : requirements.values()) {
            if (!contextCanProvideSelectedValue(context, selectedValue)) {
                return false;
            }
        }
        return true;
    }

    private boolean contextCanProvideSelectedValue(Map<String, Object> context, SelectedValue selectedValue) {
        for (Alternative alt : selectedValue.alternatives()) {
            if (contextCanProvideAlternative(context, alt)) {
                return true;
            }
        }
        return false;
    }

    private boolean contextCanProvideAlternative(Map<String, Object> context, Alternative alt) {
        return switch (alt) {
            case Path path -> contextHasPathSource(context, path);
            case ObjectSelection obj -> contextCanProvideObjectSelection(context, obj);
            case ListSelection list -> list.pathPrefix() != null
                && contextHasPathSource(context, list.pathPrefix());
        };
    }

    private boolean contextCanProvideObjectSelection(Map<String, Object> context, ObjectSelection obj) {
        if (obj.pathPrefix() != null) {
            return contextHasPathSource(context, obj.pathPrefix());
        }
        for (ObjectField field : obj.fields()) {
            if (!contextCanProvideSelectedValue(context, field.value())) {
                return false;
            }
        }
        return true;
    }

    private boolean contextHasPathSource(Map<String, Object> context, Path path) {
        if (path.hasInitialTypeCondition()) {
            String typename = (String) context.get(IntrospectionFields.TYPENAME);
            if (typename == null || !path.initialTypeCondition().equals(typename)) {
                return false;
            }
        }
        if (path.segments().isEmpty()) {
            return true;
        }
        return context.containsKey(path.segments().get(0).fieldName());
    }

    /**
     * Finds matching contexts in merged data when the direct parent context is a
     * wrapper object around the actual entity, such as { container: { book } }.
     */
    private List<Map<String, Object>> findMatchingContexts(Map<String, Object> data,
                                                            Map<String, SelectedValue> requirements) {
        List<Map<String, Object>> result = new ArrayList<>();
        findMatchingContextsRecursive(data, requirements, result);
        return result;
    }

    private void findMatchingContextsRecursive(Object data, Map<String, SelectedValue> requirements,
                                               List<Map<String, Object>> result) {
        if (data instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) data;

            if (contextCanProvideRequirements(map, requirements)) {
                result.add(map);
                return;
            }

            for (Object value : map.values()) {
                findMatchingContextsRecursive(value, requirements, result);
            }
        } else if (data instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) data;
            for (Object item : list) {
                findMatchingContextsRecursive(item, requirements, result);
            }
        }
    }

    /**
     * Extracts context candidates below a lookup root. The lookup root object is
     * not itself added because repeated lookup results are merged into the
     * original parent context; only objects nested beneath that result can be
     * independent contexts for later lookups.
     */
    private List<Map<String, Object>> extractNestedContexts(Map<String, Object> data) {
        List<Map<String, Object>> nested = new ArrayList<>();
        for (Object value : data.values()) {
            extractNestedContextsRecursive(value, nested, false);
        }
        return nested;
    }

    private void extractNestedContextsRecursive(Object data, List<Map<String, Object>> result,
                                                boolean includeCurrent) {
        if (data instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) data;

            if (includeCurrent) {
                result.add(map);
            }

            for (Object value : map.values()) {
                extractNestedContextsRecursive(value, result, true);
            }
        } else if (data instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) data;
            for (Object item : list) {
                extractNestedContextsRecursive(item, result, true);
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
                context.putAll(innerData);
            }
        }
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
        // The first selection is typically the lookup field (e.g., bookById)
        for (Selection<?> selection : operation.getSelectionSet().getSelections()) {
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
     * Deep merges source into target.
     */
    private void deepMerge(Map<String, Object> target, Map<String, Object> source) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            Object sourceValue = entry.getValue();

            if (target.containsKey(key)) {
                Object targetValue = target.get(key);
                if (targetValue instanceof Map && sourceValue instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> targetMap = (Map<String, Object>) targetValue;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> sourceMap = (Map<String, Object>) sourceValue;
                    deepMerge(targetMap, sourceMap);
                } else {
                    target.put(key, sourceValue);
                }
            } else {
                target.put(key, sourceValue);
            }
        }
    }

    /**
     * Holds result from executing a single step.
     */
    private static class StepResult {
        private final int stepId;
        private Map<String, Object> rootData;
        private Map<String, Object> stepData;
        private List<Map<String, Object>> dataContexts;
        private List<Map<String, Object>> nestedContexts;
        private final List<GraphQLError> errors = new ArrayList<>();

        StepResult(int stepId) {
            this.stepId = stepId;
        }

        int getStepId() { return stepId; }
        Map<String, Object> getRootData() { return rootData; }
        Map<String, Object> getStepData() { return stepData; }
        List<Map<String, Object>> getDataContexts() { return dataContexts; }
        List<Map<String, Object>> getNestedContexts() { return nestedContexts; }
        List<GraphQLError> getErrors() { return errors; }

        void setRootData(Map<String, Object> data) { this.rootData = data; }
        void setStepData(Map<String, Object> data) { this.stepData = data; }
        void setDataContexts(List<Map<String, Object>> contexts) { this.dataContexts = contexts; }
        void setNestedContexts(List<Map<String, Object>> contexts) { this.nestedContexts = contexts; }
        void addError(GraphQLError error) { this.errors.add(error); }
    }

    /**
     * Result from a single entity execution in a repeated step.
     * @param context the entity context that was used for this execution
     * @param result the execution result, or null if skipped/failed
     * @param error optional error if the execution failed (e.g., timeout)
     * @param skipErrors optional GraphQL errors produced when skipping due to null @require values
     */
    private record EntityResult(Map<String, Object> context, ExecutionResult result, Throwable error,
                                List<GraphQLError> skipErrors) {
        EntityResult(Map<String, Object> context, ExecutionResult result) {
            this(context, result, null, null);
        }

        EntityResult(Map<String, Object> context, ExecutionResult result, Throwable error) {
            this(context, result, error, null);
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
        private final Map<String, Object> mergedData = new LinkedHashMap<>();
        private final Map<Integer, Map<String, Object>> stepDataMap = new ConcurrentHashMap<>();
        private final Map<Integer, List<Map<String, Object>>> dataContextsMap = new ConcurrentHashMap<>();
        private final Map<Integer, List<Map<String, Object>>> nestedContextsMap = new ConcurrentHashMap<>();
        private final List<GraphQLError> errors = new CopyOnWriteArrayList<>();

        ExecutionContext() {}

        synchronized void merge(StepResult result) {
            if (result.getRootData() != null) {
                mergedData.putAll(result.getRootData());
            }
            if (result.getStepData() != null) {
                deepMerge(mergedData, result.getStepData());
                stepDataMap.put(result.getStepId(), result.getStepData());
            }
            if (result.getDataContexts() != null) {
                dataContextsMap.put(result.getStepId(), result.getDataContexts());
            }
            if (result.getNestedContexts() != null) {
                nestedContextsMap.put(result.getStepId(), result.getNestedContexts());
            }
            if (result.getErrors() != null && !result.getErrors().isEmpty()) {
                errors.addAll(result.getErrors());
            }
        }

        Map<String, Object> getMergedData() { return mergedData; }
        Map<String, Object> getStepData(int stepId) { return stepDataMap.get(stepId); }
        List<Map<String, Object>> getDataContexts(int stepId) { return dataContextsMap.get(stepId); }
        List<Map<String, Object>> getNestedContexts(int stepId) { return nestedContextsMap.get(stepId); }
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
