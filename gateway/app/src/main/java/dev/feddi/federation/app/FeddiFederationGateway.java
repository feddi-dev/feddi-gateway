package dev.feddi.federation.app;

import dev.feddi.federation.app.OperationPlanCache.PreparedOperation;
import dev.feddi.federation.extension.DocumentProvider;
import dev.feddi.federation.extension.FeddiGatewayRequestContext;
import dev.feddi.federation.extension.SubgraphClient;
import dev.feddi.federation.engine.compose.Composer;
import dev.feddi.federation.engine.compose.Composer.SubgraphInput;
import dev.feddi.federation.engine.compose.CompositionResult;
import dev.feddi.federation.engine.compose.CustomScalarWiring;
import dev.feddi.federation.engine.compose.SubgraphParser;
import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.executor.BatchingOptions;
import dev.feddi.federation.engine.executor.ExecutionListener;
import dev.feddi.federation.engine.executor.Executor;
import dev.feddi.federation.engine.executor.NonNullPropagation;
import dev.feddi.federation.engine.executor.ResponseFieldOrder;
import dev.feddi.federation.engine.executor.ResponseShape;
import dev.feddi.federation.engine.executor.ResponseWriter;
import dev.feddi.federation.engine.graph.Graph;
import dev.feddi.federation.engine.graph.GraphBuilder;
import dev.feddi.federation.engine.planner.ExecutionPlan;
import dev.feddi.federation.engine.query.Operation;
import dev.feddi.federation.engine.query.OperationNormalizer;
import dev.feddi.federation.engine.planner.OperationPlanner;
import graphql.ErrorType;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.ParseAndValidate;
import graphql.language.Document;
import graphql.language.OperationDefinition;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Federation Gateway that composes subgraph schemas and executes queries.
 *
 * This is the main entry point for federated query execution.
 */
public final class FeddiFederationGateway {

    private static final Logger log = LoggerFactory.getLogger(FeddiFederationGateway.class);

    private final Graph graph;
    private final GraphQLSchema supergraph;
    private final boolean introspectionEnabled;
    private final OperationPlanner planner;
    private final OperationNormalizer normalizer;
    private final Map<String, SubgraphClient> extensionClients;
    private final ExecutionListener executionListener;
    private final FeddiGatewayMetrics gatewayMetrics;
    private final DocumentProvider documentProvider;
    private final OperationPlanCache planCache;
    private final Map<String, BatchingOptions> batching;
    /** The subgraphs configured with {@code reuseResults: false}. */
    private final Set<String> withoutResultReuse;

    private FeddiFederationGateway(Graph graph, GraphQLSchema supergraph,
                                   Map<String, SubgraphClient> subgraphClients,
                                   ExecutionListener executionListener) {
        this(graph, supergraph, subgraphClients, executionListener, null, null, true);
    }

    private FeddiFederationGateway(Graph graph, GraphQLSchema supergraph,
                                   Map<String, SubgraphClient> subgraphClients,
                                   ExecutionListener executionListener,
                                   FeddiGatewayMetrics gatewayMetrics,
                                   DocumentProvider documentProvider) {
        this(graph, supergraph, subgraphClients, executionListener, gatewayMetrics, documentProvider, true);
    }

    private FeddiFederationGateway(Graph graph, GraphQLSchema supergraph,
                                   Map<String, SubgraphClient> subgraphClients,
                                   ExecutionListener executionListener,
                                   FeddiGatewayMetrics gatewayMetrics,
                                   DocumentProvider documentProvider,
                                   boolean introspectionEnabled) {
        this.graph = graph;
        this.supergraph = supergraph;
        this.introspectionEnabled = introspectionEnabled;
        this.planner = new OperationPlanner(graph);
        // Normalizer to inline fragments, deduplicate fields, etc.
        this.normalizer = OperationNormalizer.builder(supergraph)
            .inlineFragments(true)
            .deduplicateFields(true)
            .sortSelections(false)  // Preserve query field order
            .build();
        // Store extension clients — adapted per-request in execute() with context
        this.extensionClients = new LinkedHashMap<>(subgraphClients);
        this.executionListener = executionListener != null ? executionListener : ExecutionListener.NOOP;
        this.gatewayMetrics = gatewayMetrics;
        this.documentProvider = documentProvider;
        this.planCache = new OperationPlanCache();
        this.batching = Map.of();
        this.withoutResultReuse = Set.of();
    }

    // Copy constructor for withSubgraphBatching and withoutResultReuse: must copy every field of the gateway.
    private FeddiFederationGateway(FeddiFederationGateway source, Map<String, BatchingOptions> batching,
                                   Set<String> withoutResultReuse) {
        this.graph = source.graph;
        this.supergraph = source.supergraph;
        this.introspectionEnabled = source.introspectionEnabled;
        this.planner = source.planner;
        this.normalizer = source.normalizer;
        this.extensionClients = source.extensionClients;
        this.executionListener = source.executionListener;
        this.gatewayMetrics = source.gatewayMetrics;
        this.documentProvider = source.documentProvider;
        this.planCache = source.planCache;
        this.batching = Map.copyOf(batching);
        this.withoutResultReuse = Set.copyOf(withoutResultReuse);
    }

    /**
     * Returns a gateway that batches entity lookups per subgraph as configured. Subgraphs
     * without an entry use {@link BatchingOptions#NONE}.
     *
     * @param batching batching options by subgraph name
     * @return a new gateway sharing everything else with this one
     */
    public FeddiFederationGateway withSubgraphBatching(Map<String, BatchingOptions> batching) {
        return new FeddiFederationGateway(this, batching, withoutResultReuse);
    }

    /**
     * Returns a gateway whose lookups to these subgraphs never take entities from other steps' results.
     *
     * @param subgraphNames the subgraphs configured with {@code reuseResults: false}
     * @return a new gateway sharing everything else with this one
     */
    public FeddiFederationGateway withoutResultReuse(Set<String> subgraphNames) {
        return new FeddiFederationGateway(this, batching, subgraphNames);
    }

    /**
     * Returns the batching options for a subgraph.
     */
    public BatchingOptions batching(String subgraphName) {
        return batching.getOrDefault(subgraphName, BatchingOptions.NONE);
    }

    /**
     * Whether lookups to a subgraph may take entities from other steps' results of it ({@code reuseResults}).
     */
    public boolean reusesResults(String subgraphName) {
        return !withoutResultReuse.contains(subgraphName);
    }
    
    /**
     * Creates a new FeddiFederationGateway from subgraph inputs and clients.
     *
     * @param subgraphInputs the subgraph schemas to compose
     * @param subgraphClients map of subgraph name to client
     * @return the configured gateway
     * @throws CompositionException if schema composition fails
     */
    public static FeddiFederationGateway create(List<SubgraphInput> subgraphInputs,
                                                Map<String, SubgraphClient> subgraphClients) {
        return create(subgraphInputs, subgraphClients, ExecutionListener.NOOP);
    }

    /**
     * Creates a new FeddiFederationGateway from subgraph inputs, clients, and an execution listener.
     *
     * @param subgraphInputs the subgraph schemas to compose
     * @param subgraphClients map of subgraph name to client
     * @param executionListener listener for execution events (metrics, logging, etc.)
     * @return the configured gateway
     * @throws CompositionException if schema composition fails
     */
    public static FeddiFederationGateway create(List<SubgraphInput> subgraphInputs,
                                                Map<String, SubgraphClient> subgraphClients,
                                                ExecutionListener executionListener) {
        return create(subgraphInputs, subgraphClients, executionListener, null);
    }

    /**
     * Creates a new FeddiFederationGateway from subgraph inputs, clients, execution listener, and feddi Gateway metrics.
     *
     * @param subgraphInputs the subgraph schemas to compose
     * @param subgraphClients map of subgraph name to client
     * @param executionListener listener for execution events (metrics, logging, etc.)
     * @param gatewayMetrics feddi Gateway metrics for planning duration recording (may be null)
     * @return the configured gateway
     * @throws CompositionException if schema composition fails
     */
    public static FeddiFederationGateway create(List<SubgraphInput> subgraphInputs,
                                                Map<String, SubgraphClient> subgraphClients,
                                                ExecutionListener executionListener,
                                                FeddiGatewayMetrics gatewayMetrics) {
        return create(subgraphInputs, subgraphClients, executionListener, gatewayMetrics, null);
    }

    /**
     * Creates a new FeddiFederationGateway from subgraph inputs, clients, execution listener, feddi Gateway metrics,
     * and an optional document provider.
     *
     * @param subgraphInputs the subgraph schemas to compose
     * @param subgraphClients map of subgraph name to client
     * @param executionListener listener for execution events (metrics, logging, etc.)
     * @param gatewayMetrics feddi Gateway metrics for planning duration recording (may be null)
     * @param documentProvider optional provider for pre-parsed documents (may be null)
     * @return the configured gateway
     * @throws CompositionException if schema composition fails
     */
    public static FeddiFederationGateway create(List<SubgraphInput> subgraphInputs,
                                                Map<String, SubgraphClient> subgraphClients,
                                                ExecutionListener executionListener,
                                                FeddiGatewayMetrics gatewayMetrics,
                                                DocumentProvider documentProvider) {
        return create(subgraphInputs, subgraphClients, executionListener, gatewayMetrics, documentProvider, true);
    }

    public static FeddiFederationGateway create(List<SubgraphInput> subgraphInputs,
                                                Map<String, SubgraphClient> subgraphClients,
                                                ExecutionListener executionListener,
                                                FeddiGatewayMetrics gatewayMetrics,
                                                DocumentProvider documentProvider,
                                                boolean introspectionEnabled) {
        Composer composer = new Composer();
        CompositionResult result = composer.compose(subgraphInputs);

        if (!result.isSuccess()) {
            throw new CompositionException("Schema composition failed: " +
                result.validationResult().diagnostics());
        }

        return new FeddiFederationGateway(result.graph(), result.supergraph(), subgraphClients,
            executionListener, gatewayMetrics, documentProvider, introspectionEnabled);
    }
    
    /**
     * Creates a new FeddiFederationGateway using a pre-composed supergraph SDL from the control plane.
     * Skips schema merging and validation (already done by the control plane), but still builds
     * the planning graph from subgraph schemas.
     *
     * @param supergraphSdl the pre-composed supergraph SDL
     * @param subgraphInputs the subgraph schemas (needed to build the planning graph)
     * @param subgraphClients map of subgraph name to client
     * @return the configured gateway
     */
    public static FeddiFederationGateway createWithPreComposedSupergraph(
            String supergraphSdl,
            List<SubgraphInput> subgraphInputs,
            Map<String, SubgraphClient> subgraphClients) {
        return createWithPreComposedSupergraph(supergraphSdl, subgraphInputs, subgraphClients, ExecutionListener.NOOP, null, null);
    }

    /**
     * Creates a new FeddiFederationGateway using a pre-composed supergraph SDL from the control plane,
     * with an execution listener for metrics.
     *
     * @param supergraphSdl the pre-composed supergraph SDL
     * @param subgraphInputs the subgraph schemas (needed to build the planning graph)
     * @param subgraphClients map of subgraph name to client
     * @param executionListener listener for execution events (metrics, logging, etc.)
     * @param gatewayMetrics feddi Gateway metrics for planning duration recording (may be null)
     * @return the configured gateway
     */
    public static FeddiFederationGateway createWithPreComposedSupergraph(
            String supergraphSdl,
            List<SubgraphInput> subgraphInputs,
            Map<String, SubgraphClient> subgraphClients,
            ExecutionListener executionListener,
            FeddiGatewayMetrics gatewayMetrics) {
        return createWithPreComposedSupergraph(supergraphSdl, subgraphInputs, subgraphClients,
            executionListener, gatewayMetrics, null);
    }

    /**
     * Creates a new FeddiFederationGateway using a pre-composed supergraph SDL from the control plane,
     * with an execution listener, metrics, and an optional document provider.
     *
     * @param supergraphSdl the pre-composed supergraph SDL
     * @param subgraphInputs the subgraph schemas (needed to build the planning graph)
     * @param subgraphClients map of subgraph name to client
     * @param executionListener listener for execution events (metrics, logging, etc.)
     * @param gatewayMetrics feddi Gateway metrics for planning duration recording (may be null)
     * @param documentProvider optional provider for pre-parsed documents (may be null)
     * @return the configured gateway
     */
    public static FeddiFederationGateway createWithPreComposedSupergraph(
            String supergraphSdl,
            List<SubgraphInput> subgraphInputs,
            Map<String, SubgraphClient> subgraphClients,
            ExecutionListener executionListener,
            FeddiGatewayMetrics gatewayMetrics,
            DocumentProvider documentProvider) {

        log.info("Creating feddi Gateway from pre-composed supergraph ({} subgraphs)", subgraphInputs.size());

        // Parse the pre-composed supergraph SDL into a GraphQLSchema
        var schemaParser = new SchemaParser();
        var typeDefinitionRegistry = schemaParser.parse(supergraphSdl);
        var supergraph = new SchemaGenerator().makeExecutableSchema(
                typeDefinitionRegistry, CustomScalarWiring.runtimeWiring());

        // Parse subgraph schemas and build the planning graph
        // (GraphBuilder is fast — it just traverses ASTs to build edges)
        var subgraphParser = new SubgraphParser();
        List<Subgraph> subgraphs = subgraphInputs.stream()
                .map(input -> subgraphParser.parse(input.name(), input.url(), input.sdl()))
                .toList();
        var graph = new GraphBuilder().build(subgraphs);

        log.info("Gateway created from pre-composed supergraph with {} subgraphs", subgraphs.size());
        return new FeddiFederationGateway(graph, supergraph, subgraphClients, executionListener,
            gatewayMetrics, documentProvider);
    }

    public static FeddiFederationGateway createWithPreComposedSupergraph(
            String supergraphSdl,
            List<SubgraphInput> subgraphInputs,
            Map<String, SubgraphClient> subgraphClients,
            ExecutionListener executionListener,
            FeddiGatewayMetrics gatewayMetrics,
            DocumentProvider documentProvider,
            boolean introspectionEnabled) {

        log.info("Creating feddi Gateway from pre-composed supergraph ({} subgraphs, introspection={})",
                subgraphInputs.size(), introspectionEnabled);

        var schemaParser = new SchemaParser();
        var typeDefinitionRegistry = schemaParser.parse(supergraphSdl);
        var supergraph = new SchemaGenerator().makeExecutableSchema(
                typeDefinitionRegistry, CustomScalarWiring.runtimeWiring());

        var subgraphParser = new SubgraphParser();
        List<Subgraph> subgraphs = subgraphInputs.stream()
                .map(input -> subgraphParser.parse(input.name(), input.url(), input.sdl()))
                .toList();
        var graph = new GraphBuilder().build(subgraphs);

        log.info("Gateway created from pre-composed supergraph with {} subgraphs", subgraphs.size());
        return new FeddiFederationGateway(graph, supergraph, subgraphClients, executionListener,
            gatewayMetrics, documentProvider, introspectionEnabled);
    }

    /**
     * Result of gateway execution: the GraphQL result and the parsed document. The response is written as JSON
     * straight from the executor's data ({@link #toJson()}); {@link #executionResult()} completes the data as maps
     * (internal fields removed, nulls propagated, fields in selection order) when asked for.
     */
    public static final class GatewayResult {
        private final ExecutionResult rawResult;
        private final @Nullable Document document;
        private final @Nullable PreparedOperation prepared;
        private final GraphQLSchema supergraph;
        private @Nullable ExecutionResult completed;

        private GatewayResult(ExecutionResult rawResult, @Nullable Document document,
                              @Nullable PreparedOperation prepared, GraphQLSchema supergraph) {
            this.rawResult = rawResult;
            this.document = document;
            this.prepared = prepared;
            this.supergraph = supergraph;
        }

        /** The parsed document, or null if the request failed before it had one. */
        public @Nullable Document document() {
            return document;
        }

        /** The completed result. */
        public ExecutionResult executionResult() {
            if (completed == null) {
                completed = prepared == null ? rawResult : completed(supergraph,
                    Executor.withoutArtificialFields(Executor.decodeStrings(rawResult), prepared.plan()),
                    prepared.operation());
            }
            return completed;
        }

        /** The response as JSON, written in one pass. */
        public ResponseWriter.Output toJson() {
            if (prepared == null) {
                // The request failed before execution
                return ResponseWriter.writeErrors(rawResult.getErrors());
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> data = rawResult.getData() instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
            return ResponseWriter.write(prepared.shape(), data, rawResult.getErrors(), rawResult.getExtensions());
        }
    }

    /**
     * Executes a GraphQL query against the federated schema.
     *
     * @param executionInput the execution input containing query and variables
     * @return the gateway result wrapped in a Mono
     */
    public Mono<GatewayResult> execute(ExecutionInput executionInput) {
        return execute(executionInput, FeddiGatewayRequestContext.empty());
    }

    public Mono<GatewayResult> execute(ExecutionInput executionInput, FeddiGatewayRequestContext requestContext) {
        Map<String, Object> variables = executionInput.getVariables();

        // Resolve the prepared operation: try the provider first (persisted documents),
        // then the plan cache by query text, then ParseAndValidate + normalize + plan.
        Mono<PreparedOperation> preparedMono;
        if (documentProvider != null) {
            preparedMono = documentProvider.getDocument(executionInput, requestContext)
                .flatMap(entry -> {
                    if (entry.hasErrors()) {
                        return Mono.<PreparedOperation>error(new DocumentResolutionException(entry.getErrors()));
                    }
                    log.debug("Using document from DocumentProvider");
                    Document providedDocument = entry.getDocument();
                    Map<String, Boolean> conditions = OperationNormalizer.conditionValues(providedDocument, variables);
                    return Mono.fromCallable(() -> planCache.getOrPrepare(providedDocument, conditions,
                        () -> prepare(providedDocument, conditions)));
                })
                .switchIfEmpty(Mono.defer(() -> prepareFromText(executionInput)));
        } else {
            preparedMono = Mono.defer(() -> prepareFromText(executionInput));
        }

        return preparedMono.flatMap(prepared -> {
            Document document = prepared.document();
            ExecutionPlan plan = prepared.plan();

            // Adapt clients per-request with the gateway request context
            var engineClients = new LinkedHashMap<String, dev.feddi.federation.engine.executor.SubgraphClient>();
            for (var entry : extensionClients.entrySet()) {
                engineClients.put(entry.getKey(),
                    new SubgraphClientAdapter(entry.getValue(), requestContext, batching(entry.getKey()),
                        reusesResults(entry.getKey())));
            }

            // Execute the plan (pass supergraph schema only if introspection is enabled)
            log.debug("Executing query plan");
            Executor executor = new Executor(engineClients,
                    introspectionEnabled ? supergraph : null, executionListener);
            final Document finalDoc = document;
            return executor.executeWithInternalFields(plan, variables != null ? variables : Map.of())
                .map(result -> new GatewayResult(result, finalDoc, prepared, supergraph));
        }).onErrorResume(DocumentResolutionException.class, e -> {
            log.debug("Document resolution failed: {}", e.getErrors());
            return Mono.just(new GatewayResult(
                ExecutionResultImpl.newExecutionResult().addErrors(e.getErrors()).build(), null, null, supergraph));
        });
    }

    private Mono<PreparedOperation> prepareFromText(ExecutionInput executionInput) {
        String query = executionInput.getQuery();
        String operationName = executionInput.getOperationName();
        Document validated = planCache.getDocument(query, operationName);
        Mono<Document> document = validated != null ? Mono.just(validated)
            : parseAndValidate(executionInput).doOnNext(d -> planCache.putDocument(query, operationName, d));
        return document.map(d -> {
            Map<String, Boolean> conditions = OperationNormalizer.conditionValues(d, executionInput.getVariables());
            PreparedOperation cached = planCache.getByText(query, operationName, conditions);
            if (cached != null) {
                log.debug("Using cached plan");
                return cached;
            }
            PreparedOperation prepared = prepare(d, conditions);
            planCache.putByText(query, operationName, conditions, prepared);
            return prepared;
        });
    }

    /**
     * Normalizes a validated document, with its @skip/@include conditions evaluated for these variable values, and
     * plans it.
     */
    private PreparedOperation prepare(Document rawDocument, Map<String, Boolean> conditions) {
        // Normalize the document (inline fragments, deduplicate fields, @skip/@include, etc.)
        log.debug("Normalizing query");
        Document document;
        try {
            document = normalizer.withConditionValues(conditions).normalize(rawDocument);
        } catch (OperationNormalizer.MissingConditionValueException e) {
            throw new DocumentResolutionException(List.of(GraphqlErrorBuilder.newError()
                .message(e.getMessage())
                .errorType(ErrorType.ValidationError)
                .build()));
        }

        OperationDefinition operationDef = document.getDefinitionsOfType(OperationDefinition.class)
            .stream()
            .findFirst()
            .orElseThrow(() -> {
                log.error("No operation found in query");
                return new ExecutionException("No operation found in query");
            });

        // Convert to our Operation model
        log.debug("Converting to Operation model");
        Operation query = Operation.fromOperationDefinition(operationDef);

        // Plan the query
        log.debug("Planning query execution");
        var planningSample = gatewayMetrics != null ? gatewayMetrics.startTimer() : null;
        try {
            ExecutionPlan plan = planner.plan(query);
            log.debug("Query planned with {} step(s)", plan.steps().size());
            for (var step : plan.steps()) {
                log.debug("  Step {}: subgraph={}, dependsOn={}",
                    step.id(), step.subgraph(), step.dependsOn());
            }
            return new PreparedOperation(document, operationDef, plan, ResponseShape.compile(supergraph, operationDef));
        } catch (Exception e) {
            log.error("Query planning failed: {}", e.getMessage(), e);
            throw e;
        } finally {
            if (planningSample != null) {
                gatewayMetrics.recordPlanningDuration(planningSample);
            }
        }
    }

    /**
     * Completes the response the executor assembled from several subgraph results: nulls in
     * non-null fields propagate to the nearest nullable parent, and the fields are put into the
     * order of the operation's selection set (the merge order otherwise determines it).
     */
    private static ExecutionResult completed(GraphQLSchema supergraph, ExecutionResult result,
                                             OperationDefinition operation) {
        ExecutionResult propagated = NonNullPropagation.apply(supergraph, operation, result);
        if (propagated.getData() == null) {
            return propagated;
        }
        return ExecutionResultImpl.newExecutionResult()
            .from(propagated)
            .data(ResponseFieldOrder.reorder(operation.getSelectionSet(), propagated.getData()))
            .build();
    }

    private Mono<Document> parseAndValidate(ExecutionInput executionInput) {
        log.debug("Parsing and validating query");
        var result = ParseAndValidate.parseAndValidate(supergraph, executionInput);
        if (result.isFailure()) {
            log.debug("Query validation failed: {}", result.getErrors());
            return Mono.error(new DocumentResolutionException(result.getErrors()));
        }
        return Mono.just(result.getDocument());
    }
    
    /**
     * Returns the composed supergraph schema.
     */
    public GraphQLSchema supergraph() {
        return supergraph;
    }
    
    /**
     * Returns the planning graph.
     */
    public Graph graph() {
        return graph;
    }
    
    /**
     * Exception thrown when schema composition fails.
     */
    public static class CompositionException extends RuntimeException {
        public CompositionException(String message) {
            super(message);
        }
    }

    /**
     * Exception thrown during query execution.
     */
    public static class ExecutionException extends RuntimeException {
        public ExecutionException(String message) {
            super(message);
        }
    }

    /**
     * Exception used internally to carry GraphQL errors through the reactive chain
     * when document resolution (provider or ParseAndValidate) fails.
     */
    private static class DocumentResolutionException extends RuntimeException {
        private final List<GraphQLError> errors;

        DocumentResolutionException(List<? extends GraphQLError> errors) {
            super("Document resolution failed");
            this.errors = List.copyOf(errors);
        }

        List<GraphQLError> getErrors() {
            return errors;
        }
    }
}
