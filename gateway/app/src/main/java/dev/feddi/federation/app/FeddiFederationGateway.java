package dev.feddi.federation.app;

import dev.feddi.federation.customization.DocumentProvider;
import dev.feddi.federation.customization.FeddiGatewayRequestContext;
import dev.feddi.federation.customization.SubgraphClient;
import dev.feddi.federation.engine.compose.Composer;
import dev.feddi.federation.engine.compose.Composer.SubgraphInput;
import dev.feddi.federation.engine.compose.CompositionResult;
import dev.feddi.federation.engine.compose.CustomScalarWiring;
import dev.feddi.federation.engine.compose.SubgraphParser;
import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.executor.ExecutionListener;
import dev.feddi.federation.engine.executor.Executor;
import dev.feddi.federation.engine.graph.Graph;
import dev.feddi.federation.engine.graph.GraphBuilder;
import dev.feddi.federation.engine.planner.ExecutionPlan;
import dev.feddi.federation.engine.query.Operation;
import dev.feddi.federation.engine.query.OperationNormalizer;
import dev.feddi.federation.engine.planner.OperationPlanner;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.GraphQLError;
import graphql.ParseAndValidate;
import graphql.language.Document;
import graphql.language.OperationDefinition;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final Map<String, SubgraphClient> customizationClients;
    private final ExecutionListener executionListener;
    private final FeddiGatewayMetrics gatewayMetrics;
    private final DocumentProvider documentProvider;

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
            .processSkipInclude(true)  // Evaluate literal @skip/@include at planning time
            .build();
        // Store customization-api clients — adapted per-request in execute() with context
        this.customizationClients = new LinkedHashMap<>(subgraphClients);
        this.executionListener = executionListener != null ? executionListener : ExecutionListener.NOOP;
        this.gatewayMetrics = gatewayMetrics;
        this.documentProvider = documentProvider;
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
     * Creates a new FeddiFederationGateway from subgraph inputs, clients, execution listener, and gateway metrics.
     *
     * @param subgraphInputs the subgraph schemas to compose
     * @param subgraphClients map of subgraph name to client
     * @param executionListener listener for execution events (metrics, logging, etc.)
     * @param gatewayMetrics gateway metrics for planning duration recording (may be null)
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
     * Creates a new FeddiFederationGateway from subgraph inputs, clients, execution listener, gateway metrics,
     * and an optional document provider.
     *
     * @param subgraphInputs the subgraph schemas to compose
     * @param subgraphClients map of subgraph name to client
     * @param executionListener listener for execution events (metrics, logging, etc.)
     * @param gatewayMetrics gateway metrics for planning duration recording (may be null)
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
     * @param gatewayMetrics gateway metrics for planning duration recording (may be null)
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
     * @param gatewayMetrics gateway metrics for planning duration recording (may be null)
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

        log.info("Creating gateway from pre-composed supergraph ({} subgraphs)", subgraphInputs.size());

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

        log.info("Creating gateway from pre-composed supergraph ({} subgraphs, introspection={})",
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
     * Result of gateway execution, containing the GraphQL result and the parsed document.
     */
    public record GatewayResult(ExecutionResult executionResult, Document document) {}

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

        // Resolve document: try provider first, fall back to ParseAndValidate
        Mono<Document> documentMono;
        if (documentProvider != null) {
            documentMono = documentProvider.getDocument(executionInput, requestContext)
                .flatMap(entry -> {
                    if (entry.hasErrors()) {
                        return Mono.<Document>error(new DocumentResolutionException(entry.getErrors()));
                    }
                    log.debug("Using document from DocumentProvider");
                    return Mono.just(entry.getDocument());
                })
                .switchIfEmpty(Mono.defer(() -> parseAndValidate(executionInput)));
        } else {
            documentMono = Mono.defer(() -> parseAndValidate(executionInput));
        }

        return documentMono.flatMap(rawDocument -> {
            // Normalize the document (inline fragments, deduplicate fields, etc.)
            log.debug("Normalizing query");
            Document document = normalizer.normalize(rawDocument);

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
            ExecutionPlan plan;
            try {
                plan = planner.plan(query);
                log.debug("Query planned with {} step(s)", plan.steps().size());
                for (var step : plan.steps()) {
                    log.debug("  Step {}: subgraph={}, dependsOn={}",
                        step.id(), step.subgraph(), step.dependsOn());
                }
            } catch (Exception e) {
                log.error("Query planning failed: {}", e.getMessage(), e);
                throw e;
            } finally {
                if (planningSample != null) {
                    gatewayMetrics.recordPlanningDuration(planningSample);
                }
            }

            // Adapt clients per-request with the gateway request context
            var engineClients = new LinkedHashMap<String, dev.feddi.federation.engine.executor.SubgraphClient>();
            for (var entry : customizationClients.entrySet()) {
                engineClients.put(entry.getKey(), new SubgraphClientAdapter(entry.getValue(), requestContext));
            }

            // Execute the plan (pass supergraph schema only if introspection is enabled)
            log.debug("Executing query plan");
            Executor executor = new Executor(engineClients,
                    introspectionEnabled ? supergraph : null, executionListener);
            final Document finalDoc = document;
            return executor.execute(plan, variables != null ? variables : Map.of())
                .map(result -> new GatewayResult(result, finalDoc));
        }).onErrorResume(DocumentResolutionException.class, e -> {
            log.debug("Document resolution failed: {}", e.getErrors());
            return Mono.just(new GatewayResult(
                ExecutionResultImpl.newExecutionResult().addErrors(e.getErrors()).build(), null));
        });
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
