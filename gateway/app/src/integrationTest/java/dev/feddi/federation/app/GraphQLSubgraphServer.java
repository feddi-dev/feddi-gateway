package dev.feddi.federation.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.schema.Coercing;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.TypeResolver;
import graphql.schema.idl.FieldWiringEnvironment;
import graphql.schema.idl.InterfaceWiringEnvironment;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.ScalarWiringEnvironment;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import graphql.schema.idl.UnionWiringEnvironment;
import graphql.schema.idl.WiringFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.springframework.web.reactive.function.server.RequestPredicates.POST;

/**
 * A Spring WebFlux functional endpoint server that executes GraphQL operations.
 *
 * <p>This server validates incoming GraphQL operations by executing them against the
 * subgraph schema using GraphQL Java. Unlike WireMock string matching, this ensures
 * that operations sent by the gateway are valid GraphQL.
 *
 * <p>Mock data is returned through a {@link MockDataWiringFactory} that navigates
 * expected response data based on field names and aliases.
 */
public final class GraphQLSubgraphServer {

    /**
     * Federation directive definitions per the Composite Schemas spec.
     */
    private static final String FEDERATION_DIRECTIVES = """
        scalar FieldSelectionSet
        scalar FieldSelectionMap
        directive @key(fields: FieldSelectionSet!) repeatable on OBJECT | INTERFACE
        directive @lookup on FIELD_DEFINITION
        directive @is(field: FieldSelectionMap!) on ARGUMENT_DEFINITION
        directive @require(field: FieldSelectionMap!) on ARGUMENT_DEFINITION
        directive @internal on OBJECT | FIELD_DEFINITION | INPUT_OBJECT | INPUT_FIELD_DEFINITION | ENUM | ENUM_VALUE | SCALAR | UNION | INTERFACE
        directive @shareable repeatable on OBJECT | FIELD_DEFINITION
        directive @inaccessible on OBJECT | FIELD_DEFINITION | INTERFACE | UNION | ARGUMENT_DEFINITION | SCALAR | ENUM | ENUM_VALUE | INPUT_OBJECT | INPUT_FIELD_DEFINITION
        directive @external on OBJECT | FIELD_DEFINITION
        directive @provides(fields: FieldSelectionSet!) on FIELD_DEFINITION
        directive @override(from: String!) on FIELD_DEFINITION
        """;

    private final String subgraphName;
    private final TypeDefinitionRegistry typeRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private DisposableServer server;
    // CopyOnWriteArrayList because Reactor Netty dispatches each request on a
    // separate ctor-http-nio-N thread, and concurrent ArrayList.add() races
    // produce ArrayIndexOutOfBoundsException ("Index N out of bounds for
    // length M") that surfaces as a 500 to the calling gateway and shows up
    // as a flaky cross-subgraph test failure.
    private final List<StubConfiguration> stubs = new CopyOnWriteArrayList<>();
    private final List<RecordedRequest> recordedRequests = new CopyOnWriteArrayList<>();

    /**
     * Creates a new GraphQL subgraph server.
     *
     * @param subgraphName the name of the subgraph
     * @param sdl the GraphQL SDL for this subgraph
     */
    public GraphQLSubgraphServer(String subgraphName, String sdl) {
        this.subgraphName = subgraphName;
        this.typeRegistry = parseWithFederationDirectives(sdl);
    }

    /**
     * Parses SDL with federation directive support.
     */
    private static TypeDefinitionRegistry parseWithFederationDirectives(String sdl) {
        SchemaParser parser = new SchemaParser();

        // Parse federation directives
        TypeDefinitionRegistry federationRegistry = parser.parse(FEDERATION_DIRECTIVES);

        // Parse the SDL
        TypeDefinitionRegistry registry = parser.parse(sdl);

        // Merge federation directives (skip if already defined)
        federationRegistry.getDirectiveDefinitions().forEach((directiveName, definition) -> {
            if (!registry.getDirectiveDefinition(directiveName).isPresent()) {
                registry.add(definition);
            }
        });

        // Merge scalar definitions
        federationRegistry.scalars().forEach((scalarName, definition) -> {
            if (!registry.getType(scalarName).isPresent()) {
                registry.add(definition);
            }
        });

        return registry;
    }

    /**
     * Starts the server on a random available port.
     */
    public void start() {
        RouterFunction<ServerResponse> route = RouterFunctions
            .route(POST("/"), this::handleGraphQL);

        HttpHandler httpHandler = RouterFunctions.toHttpHandler(route);
        ReactorHttpHandlerAdapter adapter = new ReactorHttpHandlerAdapter(httpHandler);

        // Bind specifically to 127.0.0.1 (loopback) instead of the wildcard
        // address. With wildcard, our LISTEN coexists silently with any
        // other process's specific-address LISTEN on the same port — and
        // BSD's TCP-listener lookup prefers the more-specific match for
        // incoming localhost traffic, so client requests to localhost:port
        // get routed to the *other* process (e.g. an IDE that happens to
        // bind a port in macOS's ephemeral 49152-65535 range). Binding
        // specifically to 127.0.0.1 makes the kernel detect the conflict
        // at bind time as EADDRINUSE; port(0) then skips that port and
        // assigns a different one.
        server = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .handle(adapter)
            .bindNow();
    }

    /**
     * Returns the base URL of this server (e.g., "http://127.0.0.1:54321").
     */
    public String getUrl() {
        if (server == null) {
            throw new IllegalStateException("Server not started");
        }
        return "http://127.0.0.1:" + server.port();
    }

    /**
     * Returns the port this server is listening on.
     */
    public int getPort() {
        if (server == null) {
            throw new IllegalStateException("Server not started");
        }
        return server.port();
    }

    /**
     * Stops the server.
     */
    public void stop() {
        if (server != null) {
            server.disposeNow();
            server = null;
        }
    }

    /**
     * Configures a stub response for a specific operation and variables.
     */
    public void stubFor(String operation, Map<String, Object> variables, Map<String, Object> response,
                        Long delayMs, String failWithError) {
        stubs.add(new StubConfiguration(operation, variables, response, delayMs, failWithError));
    }

    /**
     * Clears all configured stubs.
     */
    public void resetStubs() {
        stubs.clear();
        recordedRequests.clear();
    }

    /**
     * Returns all recorded requests (for debugging).
     */
    public List<RecordedRequest> getRecordedRequests() {
        return List.copyOf(recordedRequests);
    }

    /**
     * Handles incoming GraphQL requests.
     */
    private Mono<ServerResponse> handleGraphQL(ServerRequest request) {
        return request.bodyToMono(String.class)
            .flatMap(body -> {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> requestBody = objectMapper.readValue(body, Map.class);
                    String query = (String) requestBody.get("query");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> variables = (Map<String, Object>) requestBody.getOrDefault("variables", Map.of());
                    if (variables == null) {
                        variables = Map.of();
                    }

                    // Capture HTTP headers
                    Map<String, String> headers = new HashMap<>();
                    request.headers().asHttpHeaders().forEach((name, values) -> {
                        if (!values.isEmpty()) {
                            headers.put(name.toLowerCase(), values.getFirst());
                        }
                    });
                    recordedRequests.add(new RecordedRequest(query, variables, headers));

                    // Find matching stub
                    StubConfiguration matchingStub = findMatchingStub(query, variables);
                    if (matchingStub == null) {
                        String error = buildNoMatchError(query, variables);
                        return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(Map.of("errors", List.of(Map.of("message", error))));
                    }

                    // Execute through GraphQL Java with mock data
                    return executeGraphQL(query, variables, matchingStub);
                } catch (JsonProcessingException e) {
                    return ServerResponse.badRequest()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("errors", List.of(Map.of("message", "Invalid JSON: " + e.getMessage()))));
                } catch (Exception e) {
                    return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("errors", List.of(Map.of("message", "Error: " + e.getMessage()))));
                }
            });
    }

    private String buildNoMatchError(String query, Map<String, Object> variables) {
        StringBuilder sb = new StringBuilder();
        sb.append("No matching stub for subgraph '").append(subgraphName).append("':\n");
        sb.append("  Query (normalized): ").append(normalizeQuery(query)).append("\n");
        sb.append("  Variables: ").append(variables).append("\n");
        sb.append("Configured stubs:\n");
        for (StubConfiguration stub : stubs) {
            sb.append("  - ").append(normalizeQuery(stub.operation())).append(" vars=").append(stub.variables()).append("\n");
        }
        return sb.toString();
    }

    /**
     * Finds a stub configuration that matches the given query and variables.
     */
    private StubConfiguration findMatchingStub(String query, Map<String, Object> variables) {
        String normalizedQuery = normalizeQuery(query);

        for (StubConfiguration stub : stubs) {
            String normalizedStubQuery = normalizeQuery(stub.operation());
            if (normalizedQuery.equals(normalizedStubQuery) && variablesMatch(variables, stub.variables())) {
                return stub;
            }
        }
        return null;
    }

    /**
     * Normalizes a GraphQL query for comparison.
     */
    private String normalizeQuery(String query) {
        try {
            var document = graphql.parser.Parser.parse(query);
            return graphql.language.AstPrinter.printAstCompact(document);
        } catch (Exception e) {
            return query.replaceAll("\\s+", " ").trim();
        }
    }

    /**
     * Checks if variables match (with type coercion for numbers/strings).
     */
    private boolean variablesMatch(Map<String, Object> actual, Map<String, Object> expected) {
        if (actual.size() != expected.size()) {
            return false;
        }
        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            Object actualValue = actual.get(entry.getKey());
            Object expectedValue = entry.getValue();
            if (!valuesMatch(actualValue, expectedValue)) {
                return false;
            }
        }
        return true;
    }

    private boolean valuesMatch(Object actual, Object expected) {
        if (actual == null && expected == null) return true;
        if (actual == null || expected == null) return false;
        if (actual.equals(expected)) return true;
        // Handle string/number coercion (IDs, etc.)
        return actual.toString().equals(expected.toString());
    }

    /**
     * Executes the GraphQL operation using GraphQL Java with mock data wiring.
     */
    private Mono<ServerResponse> executeGraphQL(String query, Map<String, Object> variables, StubConfiguration stub) {
        // Check if this stub should simulate a failure
        if (stub.shouldFail()) {
            try {
                String errorBody = objectMapper.writeValueAsString(
                    Map.of("errors", List.of(Map.of("message", stub.failWithError()))));
                Mono<ServerResponse> errorResponse = ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(errorBody);

                // Apply delay before failure if specified
                if (stub.delayMs() != null && stub.delayMs() > 0) {
                    long delay = stub.delayMs() == Long.MAX_VALUE ? 3600000L : stub.delayMs();
                    return Mono.delay(java.time.Duration.ofMillis(delay))
                        .then(errorResponse);
                }
                return errorResponse;
            } catch (JsonProcessingException e) {
                return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .bodyValue("Failed to serialize error: " + e.getMessage());
            }
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> responseData = (Map<String, Object>) stub.response().get("data");

            // Build executable schema with mock data wiring.
            // SchemaGenerator.makeExecutableSchema is called against the shared
            // typeRegistry; GraphQL Java is not documented as thread-safe for
            // concurrent schema generation off the same registry. Synchronize
            // to prevent rare concurrent-modification corruption that surfaces
            // as malformed (text/plain) responses to the calling gateway.
            GraphQLSchema executableSchema;
            synchronized (typeRegistry) {
                MockDataWiringFactory wiringFactory = new MockDataWiringFactory(responseData);
                RuntimeWiring wiring = wiringFactory.buildWiring();
                executableSchema = new SchemaGenerator()
                    .makeExecutableSchema(typeRegistry, wiring);
            }

            GraphQL graphQL = GraphQL.newGraphQL(executableSchema).build();

            ExecutionInput executionInput = ExecutionInput.newExecutionInput()
                .query(query)
                .variables(variables)
                .build();

            ExecutionResult result = graphQL.execute(executionInput);

            // Check for GraphQL execution errors (schema validation, etc.)
            if (result.getErrors() != null && !result.getErrors().isEmpty()) {
                StringBuilder errorMsg = new StringBuilder();
                errorMsg.append("GraphQL execution errors for subgraph '").append(subgraphName).append("':\n");
                for (graphql.GraphQLError error : result.getErrors()) {
                    errorMsg.append("  - ").append(error.getMessage()).append("\n");
                }
                errorMsg.append("Query: ").append(query).append("\n");
                errorMsg.append("Variables: ").append(variables);
                throw new IllegalStateException(errorMsg.toString());
            }

            // Build response body with data
            Map<String, Object> responseBody = new HashMap<>();
            responseBody.put("data", result.getData());

            // Include errors from stub response if present (for testing error propagation)
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> stubErrors = (List<Map<String, Object>>) stub.response().get("errors");
            if (stubErrors != null && !stubErrors.isEmpty()) {
                responseBody.put("errors", stubErrors);
            }

            Mono<ServerResponse> response = ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(objectMapper.writeValueAsString(responseBody));

            // Apply delay if specified
            if (stub.delayMs() != null && stub.delayMs() > 0) {
                long delay = stub.delayMs() == Long.MAX_VALUE ? 3600000L : stub.delayMs();
                return Mono.delay(java.time.Duration.ofMillis(delay))
                    .then(response);
            }

            return response;

        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("errors", List.of(Map.of("message", "Execution error: " + e.getMessage()))));
        }
    }

    /**
     * Creates an error response for unmatched requests.
     */
    private Mono<ServerResponse> createErrorResponse(String query, Map<String, Object> variables) {
        StringBuilder sb = new StringBuilder();
        sb.append("No matching stub found for subgraph '").append(subgraphName).append("':\n");
        sb.append("  Query (normalized): ").append(normalizeQuery(query)).append("\n");
        sb.append("  Variables: ").append(variables).append("\n\n");
        sb.append("Configured stubs:\n");
        for (StubConfiguration stub : stubs) {
            sb.append("  - Operation: ").append(normalizeQuery(stub.operation())).append("\n");
            sb.append("    Variables: ").append(stub.variables()).append("\n");
        }

        return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("errors", List.of(Map.of("message", sb.toString()))));
    }

    /**
     * A stub configuration for an expected operation.
     */
    public record StubConfiguration(
        String operation,
        Map<String, Object> variables,
        Map<String, Object> response,
        Long delayMs,
        String failWithError
    ) {
        /**
         * Returns true if this stub should simulate a failure.
         */
        public boolean shouldFail() {
            return failWithError != null && !failWithError.isBlank();
        }
    }

    /**
     * A recorded request (for debugging and header assertion).
     */
    public record RecordedRequest(String query, Map<String, Object> variables, Map<String, String> headers) {}

    /**
     * A WiringFactory that returns data from the expected response map.
     * Replicates the logic from engine's MockDataWiringFactory.
     */
    private static final class MockDataWiringFactory implements WiringFactory {

        private final Map<String, Object> responseData;

        private static final Set<String> BUILT_IN_SCALARS = Set.of(
            "String", "Int", "Float", "Boolean", "ID"
        );

        MockDataWiringFactory(Map<String, Object> responseData) {
            this.responseData = responseData != null ? responseData : Map.of();
        }

        RuntimeWiring buildWiring() {
            return RuntimeWiring.newRuntimeWiring()
                .wiringFactory(this)
                .build();
        }

        @Override
        public boolean providesDataFetcher(FieldWiringEnvironment environment) {
            return true;
        }

        @Override
        public DataFetcher<?> getDataFetcher(FieldWiringEnvironment environment) {
            return dataEnv -> {
                String resultKey = dataEnv.getField().getResultKey();
                Object source = dataEnv.getSource();

                if (source == null) {
                    return responseData.get(resultKey);
                } else if (source instanceof Map<?, ?> sourceMap) {
                    return sourceMap.get(resultKey);
                }
                return null;
            };
        }

        @Override
        public boolean providesTypeResolver(InterfaceWiringEnvironment environment) {
            return true;
        }

        @Override
        public TypeResolver getTypeResolver(InterfaceWiringEnvironment environment) {
            String interfaceName = environment.getInterfaceTypeDefinition().getName();
            return env -> {
                Object obj = env.getObject();
                if (!(obj instanceof Map<?, ?> objMap)) {
                    return null;
                }
                Object typename = objMap.get("__typename");
                if (typename instanceof String typeName) {
                    GraphQLObjectType type = env.getSchema().getObjectType(typeName);
                    if (type != null) {
                        return type;
                    }
                }
                throw new IllegalStateException(
                    "Mock response data for interface '" + interfaceName + "' must include __typename. Data: " + objMap);
            };
        }

        @Override
        public boolean providesTypeResolver(UnionWiringEnvironment environment) {
            return true;
        }

        @Override
        public TypeResolver getTypeResolver(UnionWiringEnvironment environment) {
            String unionName = environment.getUnionTypeDefinition().getName();
            return env -> {
                Object obj = env.getObject();
                if (!(obj instanceof Map<?, ?> objMap)) {
                    return null;
                }
                Object typename = objMap.get("__typename");
                if (typename instanceof String typeName) {
                    GraphQLObjectType type = env.getSchema().getObjectType(typeName);
                    if (type != null) {
                        return type;
                    }
                }
                throw new IllegalStateException(
                    "Mock response data for union '" + unionName + "' must include __typename. Data: " + objMap);
            };
        }

        @Override
        public boolean providesScalar(ScalarWiringEnvironment environment) {
            String name = environment.getScalarTypeDefinition().getName();
            return !BUILT_IN_SCALARS.contains(name);
        }

        @Override
        public GraphQLScalarType getScalar(ScalarWiringEnvironment environment) {
            String name = environment.getScalarTypeDefinition().getName();
            return GraphQLScalarType.newScalar()
                .name(name)
                .coercing(new PassThroughCoercing())
                .build();
        }

        private static final class PassThroughCoercing implements Coercing<Object, Object> {
            @Override
            public Object serialize(Object dataFetcherResult,
                                    graphql.GraphQLContext context,
                                    java.util.Locale locale) {
                return dataFetcherResult;
            }

            @Override
            public Object parseValue(Object input,
                                     graphql.GraphQLContext context,
                                     java.util.Locale locale) {
                return input;
            }

            @Override
            public Object parseLiteral(graphql.language.Value<?> input,
                                       graphql.execution.CoercedVariables variables,
                                       graphql.GraphQLContext context,
                                       java.util.Locale locale) {
                if (input instanceof graphql.language.StringValue sv) return sv.getValue();
                if (input instanceof graphql.language.IntValue iv) return iv.getValue();
                if (input instanceof graphql.language.FloatValue fv) return fv.getValue();
                if (input instanceof graphql.language.BooleanValue bv) return bv.isValue();
                return input;
            }
        }
    }
}
