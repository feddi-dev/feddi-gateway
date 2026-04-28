package dev.feddi.federation.engine.compose;

import dev.feddi.federation.engine.Constants;

import graphql.language.DirectiveDefinition;
import graphql.language.ScalarTypeDefinition;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.FieldWiringEnvironment;
import graphql.schema.idl.InterfaceWiringEnvironment;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.ScalarWiringEnvironment;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import graphql.schema.idl.UnionWiringEnvironment;
import graphql.schema.idl.WiringFactory;

/**
 * Parses GraphQL SDL strings into Subgraph instances using GraphQL Java.
 * Handles federation directives (@key, @lookup, @is, @require, etc.).
 */
public final class SubgraphParser {

    private final SchemaParser schemaParser;
    
    public SubgraphParser() {
        this.schemaParser = new SchemaParser();
    }
    
    /**
     * Parses an SDL string into a Subgraph.
     *
     * @param name the subgraph name/identifier
     * @param url the subgraph endpoint URL (can be null)
     * @param sdl the GraphQL SDL string
     * @return the parsed Subgraph
     */
    public Subgraph parse(String name, String url, String sdl) {
        // Preprocess SDL to handle extend schema @link directives
        String processedSdl = preprocessSdl(sdl);

        // Parse the SDL
        TypeDefinitionRegistry registry = schemaParser.parse(processedSdl);

        // Merge federation directives (they may already be defined via @link)
        mergeFederationDefinitions(registry);

        // Add a dummy Query type if one doesn't exist (required by GraphQL Java)
        ensureQueryType(registry);

        // Build the schema with empty runtime wiring (no resolvers needed for composition)
        RuntimeWiring wiring = RuntimeWiring.newRuntimeWiring()
            .wiringFactory(new NoOpWiringFactory())
            .build();

        GraphQLSchema schema = new SchemaGenerator()
            .makeExecutableSchema(registry, wiring);

        return new Subgraph(name, url, schema);
    }
    
    /**
     * Ensures a Query type exists in the registry.
     * GraphQL Java requires a Query type to build an executable schema.
     * If the schema defines a custom query root type via `schema { query: CustomType }`,
     * we don't need to add a placeholder Query type.
     */
    private void ensureQueryType(TypeDefinitionRegistry registry) {
        // Check if there's a schema definition with a custom query type
        if (registry.schemaDefinition().isPresent()) {
            var schemaDef = registry.schemaDefinition().get();
            var queryOp = schemaDef.getOperationTypeDefinitions().stream()
                .filter(op -> "query".equals(op.getName()))
                .findFirst();
            if (queryOp.isPresent()) {
                // Custom query type is defined, no need to add placeholder
                return;
            }
        }

        if (!registry.getType(Constants.QUERY).isPresent()) {
            // Add a minimal Query type with a dummy field
            registry.merge(schemaParser.parse("type Query { _placeholder: String }"));
        }
    }
    
    /**
     * Preprocesses SDL to remove non-spec directives (@link, @tag).
     * We strip these out since they're not part of the composition spec.
     */
    private String preprocessSdl(String sdl) {
        // Remove extend schema lines with @link directives
        String result = sdl.replaceAll("(?s)extend\\s+schema\\s+@link\\([^)]*\\)\\s*", "")
                           .replaceAll("(?s)extend\\s+schema\\s*\\n\\s*@link\\([^)]*\\)\\s*", "");
        
        // Remove @tag directive usages
        result = result.replaceAll("@tag\\([^)]*\\)\\s*", "");
        
        return result;
    }
    
    /**
     * Parses an SDL string into a Subgraph without a URL.
     */
    public Subgraph parse(String name, String sdl) {
        return parse(name, null, sdl);
    }
    
    /**
     * Merges federation directive and scalar definitions from {@link FederationDirectives},
     * skipping any already defined in the target registry.
     */
    private void mergeFederationDefinitions(TypeDefinitionRegistry target) {
        // Merge directive definitions
        for (DirectiveDefinition definition : FederationDirectives.ALL_DEFINITIONS) {
            if (target.getDirectiveDefinition(definition.getName()).isEmpty()) {
                target.add(definition);
            }
        }

        // Merge scalar type definitions (FieldSelectionSet, FieldSelectionMap)
        for (ScalarTypeDefinition scalar : FederationDirectives.SCALAR_DEFINITIONS) {
            if (target.getType(scalar.getName()).isEmpty()) {
                target.add(scalar);
            }
        }
    }
    
    /**
     * A WiringFactory that provides no-op data fetchers for all fields
     * and handles custom scalar types.
     * This allows building a schema without actual resolvers.
     */
    private static class NoOpWiringFactory implements WiringFactory {
        
        @Override
        public boolean providesTypeResolver(InterfaceWiringEnvironment environment) {
            return true;
        }
        
        @Override
        public graphql.schema.TypeResolver getTypeResolver(InterfaceWiringEnvironment environment) {
            return env -> null;
        }
        
        @Override
        public boolean providesTypeResolver(UnionWiringEnvironment environment) {
            return true;
        }
        
        @Override
        public graphql.schema.TypeResolver getTypeResolver(UnionWiringEnvironment environment) {
            return env -> null;
        }
        
        @Override
        public boolean providesDataFetcher(FieldWiringEnvironment environment) {
            return true;
        }
        
        @Override
        public graphql.schema.DataFetcher<?> getDataFetcher(FieldWiringEnvironment environment) {
            return env -> null;
        }
        
        @Override
        public boolean providesScalar(ScalarWiringEnvironment environment) {
            return CustomScalarWiring.isCustomScalar(environment.getScalarTypeDefinition().getName());
        }

        @Override
        public graphql.schema.GraphQLScalarType getScalar(ScalarWiringEnvironment environment) {
            return CustomScalarWiring.passThrough(environment.getScalarTypeDefinition().getName());
        }
    }
}
