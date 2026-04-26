package dev.feddi.federation.engine.compose;

import graphql.schema.Coercing;
import graphql.schema.GraphQLScalarType;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.ScalarWiringEnvironment;
import graphql.schema.idl.WiringFactory;

import java.util.Set;

/**
 * Shared wiring support for custom scalar types.
 * Custom scalars (anything not String/Int/Float/Boolean/ID) are handled as
 * pass-through: values are forwarded as-is without coercion.
 */
public final class CustomScalarWiring {

    private static final Set<String> BUILT_IN_SCALARS = Set.of(
            "String", "Int", "Float", "Boolean", "ID"
    );

    private CustomScalarWiring() {}

    /**
     * Returns true if the given scalar name is a custom (non-built-in) scalar.
     */
    public static boolean isCustomScalar(String name) {
        return !BUILT_IN_SCALARS.contains(name);
    }

    /**
     * Creates a pass-through {@link GraphQLScalarType} for the given name.
     */
    public static GraphQLScalarType passThrough(String name) {
        return GraphQLScalarType.newScalar()
                .name(name)
                .coercing(PassThroughCoercing.INSTANCE)
                .build();
    }

    /**
     * Returns a {@link RuntimeWiring} that handles custom scalars as pass-through.
     * Use this when building an executable schema that may contain custom scalar types.
     */
    public static RuntimeWiring runtimeWiring() {
        return RuntimeWiring.newRuntimeWiring()
                .wiringFactory(new WiringFactory() {
                    @Override
                    public boolean providesScalar(ScalarWiringEnvironment environment) {
                        return isCustomScalar(environment.getScalarTypeDefinition().getName());
                    }

                    @Override
                    public GraphQLScalarType getScalar(ScalarWiringEnvironment environment) {
                        return passThrough(environment.getScalarTypeDefinition().getName());
                    }
                })
                .build();
    }

    /**
     * Coercing implementation that passes values through without transformation.
     */
    static final class PassThroughCoercing implements Coercing<Object, Object> {

        static final PassThroughCoercing INSTANCE = new PassThroughCoercing();

        @Override
        public Object serialize(Object dataFetcherResult,
                                graphql.GraphQLContext context, java.util.Locale locale) {
            return dataFetcherResult;
        }

        @Override
        public Object parseValue(Object input,
                                 graphql.GraphQLContext context, java.util.Locale locale) {
            return input;
        }

        @Override
        public Object parseLiteral(graphql.language.Value<?> input,
                                   graphql.execution.CoercedVariables variables,
                                   graphql.GraphQLContext context, java.util.Locale locale) {
            if (input instanceof graphql.language.StringValue stringValue) {
                return stringValue.getValue();
            }
            return input;
        }
    }
}
