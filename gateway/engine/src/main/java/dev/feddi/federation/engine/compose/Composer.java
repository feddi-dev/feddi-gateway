package dev.feddi.federation.engine.compose;

import dev.feddi.federation.engine.graph.GraphBuilder;
import dev.feddi.federation.engine.compose.SchemaMerger.MergeValidationException;
import dev.feddi.federation.engine.graph.Graph;
import dev.feddi.federation.engine.compose.validation.Diagnostic;
import dev.feddi.federation.engine.compose.validation.SchemaValidator;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import graphql.schema.GraphQLSchema;

import java.util.ArrayList;
import java.util.List;

/**
 * Main entry point for schema composition.
 * Parses subgraph SDL strings, validates them, and produces a planning Graph.
 */
public final class Composer {
    
    private final SubgraphParser parser;
    private final SchemaValidator validator;
    private final GraphBuilder graphBuilder;
    private final SchemaMerger schemaMerger;
    
    public Composer() {
        this.parser = new SubgraphParser();
        this.validator = SchemaValidator.withDefaultRules();
        this.graphBuilder = new GraphBuilder();
        this.schemaMerger = new SchemaMerger();
    }
    
    /**
     * Composes multiple subgraph schemas into a planning graph.
     *
     * @param subgraphInputs list of subgraph inputs (name, url, SDL)
     * @return the composition result
     */
    public CompositionResult compose(List<SubgraphInput> subgraphInputs) {
        // Parse all subgraphs
        List<Subgraph> subgraphs = new ArrayList<>();
        for (SubgraphInput input : subgraphInputs) {
            Subgraph subgraph = parser.parse(input.name(), input.url(), input.sdl());
            subgraphs.add(subgraph);
        }

        // Validate source schemas and pre-merge rules
        ValidationResult validationResult = validator.validateAll(subgraphs);

        if (validationResult.hasErrors()) {
            return CompositionResult.failure(subgraphs, validationResult);
        }

        // Build the planning graph
        Graph graph = graphBuilder.build(subgraphs);

        // Merge all subgraph schemas into a supergraph
        GraphQLSchema supergraph;
        try {
            supergraph = mergeSchemas(subgraphs);
        } catch (MergeValidationException e) {
            // Merge validation failed with a specific error code
            Diagnostic error = Diagnostic.error(e.getErrorCode(), e.getMessage(), null, null);
            return CompositionResult.failure(subgraphs, ValidationResult.of(List.of(error)));
        } catch (Exception e) {
            // Schema building failed for other reasons
            Diagnostic error = Diagnostic.error(
                "SCHEMA_BUILD_ERROR",
                "Failed to build merged schema: " + e.getMessage(),
                null, null
            );
            return CompositionResult.failure(subgraphs, ValidationResult.of(List.of(error)));
        }

        // Run post-merge validation on the merged schema
        ValidationResult postMergeResult = validator.validatePostMerge(supergraph, subgraphs);
        validationResult = validationResult.merge(postMergeResult);

        if (validationResult.hasErrors()) {
            return CompositionResult.failure(subgraphs, validationResult);
        }

        // Run post-graph validation (satisfiability check)
        ValidationResult postGraphResult = validator.validatePostGraph(graph, supergraph, subgraphs);
        validationResult = validationResult.merge(postGraphResult);

        if (validationResult.hasErrors()) {
            return CompositionResult.failure(subgraphs, validationResult);
        }

        return CompositionResult.success(subgraphs, graph, supergraph, validationResult);
    }

    /**
     * Composes subgraphs from pre-parsed Subgraph instances.
     */
    public CompositionResult compose(List<Subgraph> subgraphs, boolean skipParsing) {
        if (!skipParsing) {
            throw new IllegalArgumentException("Use compose(List<SubgraphInput>) for unparsed inputs");
        }

        // Validate source schemas and pre-merge rules
        ValidationResult validationResult = validator.validateAll(subgraphs);

        if (validationResult.hasErrors()) {
            return CompositionResult.failure(subgraphs, validationResult);
        }

        // Build the planning graph
        Graph graph = graphBuilder.build(subgraphs);

        // Merge all subgraph schemas into a supergraph
        GraphQLSchema supergraph;
        try {
            supergraph = mergeSchemas(subgraphs);
        } catch (MergeValidationException e) {
            // Merge validation failed with a specific error code
            Diagnostic error = Diagnostic.error(e.getErrorCode(), e.getMessage(), null, null);
            return CompositionResult.failure(subgraphs, ValidationResult.of(List.of(error)));
        } catch (Exception e) {
            // Schema building failed for other reasons
            Diagnostic error = Diagnostic.error(
                "SCHEMA_BUILD_ERROR",
                "Failed to build merged schema: " + e.getMessage(),
                null, null
            );
            return CompositionResult.failure(subgraphs, ValidationResult.of(List.of(error)));
        }

        // Run post-merge validation on the merged schema
        ValidationResult postMergeResult = validator.validatePostMerge(supergraph, subgraphs);
        validationResult = validationResult.merge(postMergeResult);

        if (validationResult.hasErrors()) {
            return CompositionResult.failure(subgraphs, validationResult);
        }

        // Run post-graph validation (satisfiability check)
        ValidationResult postGraphResult = validator.validatePostGraph(graph, supergraph, subgraphs);
        validationResult = validationResult.merge(postGraphResult);

        if (validationResult.hasErrors()) {
            return CompositionResult.failure(subgraphs, validationResult);
        }

        return CompositionResult.success(subgraphs, graph, supergraph, validationResult);
    }

    /**
     * Merges all subgraph schemas into a single supergraph schema.
     *
     * @param subgraphs the subgraphs to merge
     * @return the merged supergraph schema
     */
    private GraphQLSchema mergeSchemas(List<Subgraph> subgraphs) {
        List<GraphQLSchema> schemas = subgraphs.stream()
            .map(Subgraph::schema)
            .toList();
        return schemaMerger.mergeAll(schemas);
    }
    
    /**
     * Input for a subgraph to be composed.
     */
    public record SubgraphInput(String name, String url, String sdl) {
        public SubgraphInput {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name cannot be null or blank");
            }
            if (sdl == null || sdl.isBlank()) {
                throw new IllegalArgumentException("sdl cannot be null or blank");
            }
        }
        
        public static SubgraphInput of(String name, String sdl) {
            return new SubgraphInput(name, null, sdl);
        }
        
        public static SubgraphInput of(String name, String url, String sdl) {
            return new SubgraphInput(name, url, sdl);
        }
    }
}
