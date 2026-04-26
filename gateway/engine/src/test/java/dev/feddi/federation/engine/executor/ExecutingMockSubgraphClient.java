package dev.feddi.federation.engine.executor;

import dev.feddi.federation.engine.testcase.SubgraphCall;
import graphql.ErrorClassification;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.GraphQLError;
import graphql.language.SourceLocation;
import graphql.GraphQL;
import graphql.language.AstPrinter;
import graphql.language.Document;
import graphql.language.OperationDefinition;
import graphql.parser.Parser;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.TypeDefinitionRegistry;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A mock SubgraphClient that executes GraphQL operations using GraphQL Java.
 *
 * <p>Unlike the string-matching MockSubgraphClient, this implementation actually
 * executes the incoming operation against the subgraph schema with mock data fetchers.
 * This validates that planner-generated operations are syntactically and semantically valid.
 *
 * <p>Benefits:
 * <ul>
 *   <li>Invalid operations fail with clear GraphQL validation errors</li>
 *   <li>Aliases, fragments, directives handled by GraphQL Java automatically</li>
 *   <li>Variables coerced according to schema types</li>
 *   <li>Proves planner-generated operations are valid GraphQL</li>
 * </ul>
 */
public final class ExecutingMockSubgraphClient implements SubgraphClient {

    private final String subgraphName;
    private final GraphQLSchema subgraphSchema;
    private final List<SubgraphCall> expectedCalls;
    private final List<RecordedCall> recordedCalls = new ArrayList<>();
    private final Parser parser = new Parser();
    private final FinishOrderController finishOrderController;

    /**
     * Creates a mock client without finish order control.
     */
    public ExecutingMockSubgraphClient(String subgraphName,
                                        GraphQLSchema subgraphSchema,
                                        List<SubgraphCall> expectedCalls) {
        this(subgraphName, subgraphSchema, expectedCalls, null);
    }

    /**
     * Creates a mock client with optional finish order control.
     */
    public ExecutingMockSubgraphClient(String subgraphName,
                                        GraphQLSchema subgraphSchema,
                                        List<SubgraphCall> expectedCalls,
                                        FinishOrderController finishOrderController) {
        this.subgraphName = subgraphName;
        this.subgraphSchema = subgraphSchema;
        this.expectedCalls = new ArrayList<>(expectedCalls);
        this.finishOrderController = finishOrderController;
    }

    @Override
    public Mono<ExecutionResult> execute(OperationDefinition operation, Map<String, Object> variables) {
        String operationText = AstPrinter.printAstCompact(operation);
        recordedCalls.add(new RecordedCall(operationText, variables));

        // Find matching expected call
        for (SubgraphCall call : expectedCalls) {
            if (operationsMatch(operationText, call.operation()) && variablesMatch(variables, call.variables())) {
                // Execute through GraphQL Java with mock data
                return executeWithMockData(operation, variables, call);
            }
        }

        // No matching call found - return error
        long delayMs = ThreadLocalRandom.current().nextLong(10, 101);
        return Mono.delay(Duration.ofMillis(delayMs))
            .flatMap(ignored -> Mono.error(new AssertionError(String.format(
                "Unexpected call to subgraph '%s':\n  Operation: %s\n  Variables: %s\n\nExpected calls:\n%s",
                subgraphName,
                operationText,
                variables,
                formatExpectedCalls()
            ))));
    }

    /**
     * Executes the operation through GraphQL Java with mock data wiring.
     */
    private Mono<ExecutionResult> executeWithMockData(OperationDefinition operation,
                                                       Map<String, Object> variables,
                                                       SubgraphCall call) {
        // Check if this call should simulate a failure (network error, etc.)
        if (call.shouldFail()) {
            long delayMs = ThreadLocalRandom.current().nextLong(10, 101);
            return Mono.delay(Duration.ofMillis(delayMs))
                .flatMap(ignored -> Mono.error(new RuntimeException(
                    "Subgraph call failed: " + call.failWithError())));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> responseData = (Map<String, Object>) call.response().get("data");

        // Check if response contains explicit errors to return
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> responseErrors =
            (List<Map<String, Object>>) call.response().get("errors");

        // Build executable schema with mock data wiring
        MockDataWiringFactory wiringFactory = new MockDataWiringFactory(responseData);
        RuntimeWiring wiring = wiringFactory.buildWiring();

        // Get the type definition registry from the schema
        TypeDefinitionRegistry registry = extractTypeDefinitionRegistry(subgraphSchema);

        GraphQLSchema executableSchema = new SchemaGenerator()
            .makeExecutableSchema(registry, wiring);

        // Validate that all interfaces have implementations - otherwise GraphQL Java may hang
        validateInterfaceImplementations(executableSchema);

        GraphQL graphQL = GraphQL.newGraphQL(executableSchema).build();

        // Build the operation as a document
        Document document = Document.newDocument()
            .definition(operation)
            .build();
        String operationString = AstPrinter.printAst(document);

        ExecutionInput executionInput = ExecutionInput.newExecutionInput()
            .query(operationString)
            .variables(variables)
            .build();

        // Execute synchronously (we'll add delays via Mono)
        ExecutionResult executedResult = graphQL.execute(executionInput);

        // Check for GraphQL errors (validation errors, missing fields, etc.)
        // Only throw on unexpected errors - if responseErrors is specified, we expect errors
        if (executedResult.getErrors() != null && !executedResult.getErrors().isEmpty()
                && responseErrors == null) {
            StringBuilder errorMsg = new StringBuilder();
            errorMsg.append("GraphQL execution errors for subgraph '").append(subgraphName).append("':\n");
            for (graphql.GraphQLError error : executedResult.getErrors()) {
                errorMsg.append("  - ").append(error.getMessage()).append("\n");
            }
            errorMsg.append("Operation: ").append(operationString);
            throw new IllegalStateException(errorMsg.toString());
        }

        // If the mock response specifies errors, build a result with those errors
        final ExecutionResult result;
        if (responseErrors != null && !responseErrors.isEmpty()) {
            List<GraphQLError> errors = responseErrors.stream()
                .map(this::mapToGraphQLError)
                .toList();
            result = ExecutionResultImpl.newExecutionResult()
                .data(executedResult.getData())
                .errors(errors)
                .build();
        } else {
            result = executedResult;
        }

        // Apply finish order control if available
        if (finishOrderController != null && call.id() != null) {
            return finishOrderController.waitForTurn(call.id())
                .thenReturn(result);
        }

        // Use explicit delay if specified (for timeout testing)
        if (call.delayMs() != null && call.delayMs() > 0) {
            long delay = call.delayMs() == Long.MAX_VALUE ? 3600000L : call.delayMs();
            return Mono.delay(Duration.ofMillis(delay))
                .map(ignored -> result);
        }

        // Fallback: random delay between 10-100ms to simulate network latency
        long delayMs = ThreadLocalRandom.current().nextLong(10, 101);
        return Mono.delay(Duration.ofMillis(delayMs))
            .map(ignored -> result);
    }

    /**
     * Extracts a TypeDefinitionRegistry from the GraphQLSchema.
     * We need this because SchemaGenerator requires a registry, not a built schema.
     */
    private TypeDefinitionRegistry extractTypeDefinitionRegistry(GraphQLSchema schema) {
        // Print the schema to SDL and re-parse it
        graphql.schema.idl.SchemaPrinter printer = new graphql.schema.idl.SchemaPrinter(
            graphql.schema.idl.SchemaPrinter.Options.defaultOptions()
                .includeDirectives(true)
                .includeDirectiveDefinitions(true)
                .includeScalarTypes(true)
        );
        String sdl = printer.print(schema);
        return new graphql.schema.idl.SchemaParser().parse(sdl);
    }

    /**
     * Validates that all interfaces in the schema have at least one implementing type.
     * If an interface has no implementations, GraphQL Java's type resolver may hang
     * during execution. This validation fails fast with a clear error message.
     */
    private void validateInterfaceImplementations(GraphQLSchema schema) {
        List<String> interfacesWithNoImpl = new ArrayList<>();

        for (graphql.schema.GraphQLType type : schema.getAllTypesAsList()) {
            if (type instanceof graphql.schema.GraphQLInterfaceType interfaceType) {
                List<graphql.schema.GraphQLObjectType> implementations =
                    schema.getImplementations(interfaceType);
                if (implementations.isEmpty()) {
                    interfacesWithNoImpl.add(interfaceType.getName());
                }
            }
        }

        if (!interfacesWithNoImpl.isEmpty()) {
            throw new IllegalStateException(
                "Subgraph '" + subgraphName + "' has interface types with no implementations: " +
                interfacesWithNoImpl + ". " +
                "This will cause GraphQL execution to hang. " +
                "Ensure that types implementing these interfaces are defined in this subgraph's schema.");
        }
    }

    /**
     * Normalizes an operation by parsing and re-printing in compact form.
     */
    private String normalizeOperation(String operation) {
        try {
            Document doc = parser.parseDocument(operation);
            return AstPrinter.printAstCompact(doc);
        } catch (Exception e) {
            // If parsing fails, fall back to simple normalization
            return operation.replaceAll("\\s+", " ").trim();
        }
    }

    /**
     * Checks if two operations match by comparing their normalized forms.
     */
    private boolean operationsMatch(String actual, String expected) {
        return normalizeOperation(actual).equals(normalizeOperation(expected));
    }

    /**
     * Checks if variables match (including type coercion for numbers).
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
        if (Objects.equals(actual, expected)) {
            return true;
        }

        // Handle string comparison (IDs might be stored differently)
        if (actual != null && expected != null) {
            return actual.toString().equals(expected.toString());
        }

        return false;
    }

    /**
     * Maps a mock error map to a GraphQLError.
     */
    private GraphQLError mapToGraphQLError(Map<String, Object> errorMap) {
        String message = (String) errorMap.getOrDefault("message", "Unknown error");

        @SuppressWarnings("unchecked")
        List<Object> pathList = (List<Object>) errorMap.get("path");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> locationsList =
            (List<Map<String, Object>>) errorMap.get("locations");

        @SuppressWarnings("unchecked")
        Map<String, Object> extensions = (Map<String, Object>) errorMap.get("extensions");

        return new GraphQLError() {
            @Override
            public String getMessage() {
                return message;
            }

            @Override
            public List<SourceLocation> getLocations() {
                if (locationsList == null) {
                    return null;
                }
                return locationsList.stream()
                    .map(loc -> new SourceLocation(
                        ((Number) loc.getOrDefault("line", 0)).intValue(),
                        ((Number) loc.getOrDefault("column", 0)).intValue()))
                    .toList();
            }

            @Override
            public ErrorClassification getErrorType() {
                return graphql.ErrorType.DataFetchingException;
            }

            @Override
            public List<Object> getPath() {
                return pathList;
            }

            @Override
            public Map<String, Object> getExtensions() {
                return extensions;
            }
        };
    }

    private String formatExpectedCalls() {
        StringBuilder sb = new StringBuilder();
        for (SubgraphCall call : expectedCalls) {
            if (call.id() != null) {
                sb.append("  - ID: ").append(call.id()).append("\n");
            }
            sb.append("  - Operation: ").append(call.operation()).append("\n");
            sb.append("    Variables: ").append(call.variables()).append("\n");
        }
        return sb.toString();
    }

    /**
     * Returns the calls that were recorded.
     */
    public List<RecordedCall> getRecordedCalls() {
        return List.copyOf(recordedCalls);
    }

    /**
     * A recorded call to this client.
     */
    public record RecordedCall(String operation, Map<String, Object> variables) {}
}
