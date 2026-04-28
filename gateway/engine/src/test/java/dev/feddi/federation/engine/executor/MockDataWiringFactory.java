package dev.feddi.federation.engine.executor;

import graphql.schema.Coercing;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.TypeResolver;
import graphql.schema.idl.FieldWiringEnvironment;
import graphql.schema.idl.InterfaceWiringEnvironment;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.ScalarWiringEnvironment;
import graphql.schema.idl.UnionWiringEnvironment;
import graphql.schema.idl.WiringFactory;

import java.util.Map;
import java.util.Set;

/**
 * A WiringFactory that wires data fetchers to return expected response data.
 * Used by ExecutingMockSubgraphClient to execute operations against mock data.
 *
 * <p>Data fetchers navigate the response map structure based on field names and aliases.
 * Type resolvers use __typename in response data for interface/union resolution.
 */
public final class MockDataWiringFactory implements WiringFactory {

    private final Map<String, Object> responseData;

    private static final Set<String> BUILT_IN_SCALARS = Set.of(
        "String", "Int", "Float", "Boolean", "ID"
    );

    /**
     * Creates a wiring factory that returns data from the given response map.
     *
     * @param responseData the response data map (typically from SubgraphCall.response().get("data"))
     */
    public MockDataWiringFactory(Map<String, Object> responseData) {
        this.responseData = responseData != null ? responseData : Map.of();
    }

    /**
     * Builds a RuntimeWiring using this factory.
     */
    public RuntimeWiring buildWiring() {
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
            // Use resultKey to handle aliases (e.g., "alias: fieldName" uses "alias" as key)
            String resultKey = dataEnv.getField().getResultKey();
            Object source = dataEnv.getSource();

            if (source == null) {
                // Root query field - look up in responseData
                return responseData.get(resultKey);
            } else if (source instanceof Map<?, ?> sourceMap) {
                // Nested field - look up in parent
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

            // Use __typename from the response data (required for interface types)
            Object typename = objMap.get("__typename");
            if (typename instanceof String typeName) {
                GraphQLObjectType type = env.getSchema().getObjectType(typeName);
                if (type != null) {
                    return type;
                }
            }

            // No __typename found - this is an error in the mock data
            throw new IllegalStateException(
                "Mock response data for interface '" + interfaceName + "' must include __typename. " +
                "Data: " + objMap);
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

            // Use __typename from the response data (required for union types)
            Object typename = objMap.get("__typename");
            if (typename instanceof String typeName) {
                GraphQLObjectType type = env.getSchema().getObjectType(typeName);
                if (type != null) {
                    return type;
                }
            }

            // No __typename found - this is an error in the mock data
            throw new IllegalStateException(
                "Mock response data for union '" + unionName + "' must include __typename. " +
                "Data: " + objMap);
        };
    }

    @Override
    public boolean providesScalar(ScalarWiringEnvironment environment) {
        // Only handle custom scalars, not built-in ones
        String name = environment.getScalarTypeDefinition().getName();
        return !BUILT_IN_SCALARS.contains(name);
    }

    @Override
    public GraphQLScalarType getScalar(ScalarWiringEnvironment environment) {
        // Create a pass-through scalar for any custom scalar type
        String name = environment.getScalarTypeDefinition().getName();
        return GraphQLScalarType.newScalar()
            .name(name)
            .coercing(new PassThroughCoercing())
            .build();
    }

    /**
     * A Coercing implementation that passes values through without transformation.
     * Used for custom scalars where we just return the mock data as-is.
     */
    private static class PassThroughCoercing implements Coercing<Object, Object> {

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
            // Extract the actual value from the AST node
            if (input instanceof graphql.language.StringValue stringValue) {
                return stringValue.getValue();
            }
            if (input instanceof graphql.language.IntValue intValue) {
                return intValue.getValue();
            }
            if (input instanceof graphql.language.FloatValue floatValue) {
                return floatValue.getValue();
            }
            if (input instanceof graphql.language.BooleanValue boolValue) {
                return boolValue.isValue();
            }
            // For other value types, return the raw value
            return input;
        }
    }
}
