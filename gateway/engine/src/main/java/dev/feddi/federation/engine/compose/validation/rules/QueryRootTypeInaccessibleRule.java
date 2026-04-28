package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.Constants;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;

import java.util.List;

import static dev.feddi.federation.engine.compose.FederationDirectives.INACCESSIBLE;

/**
 * Validates that the Query root type is not marked as @inaccessible.
 * 
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Query-Root-Type-Inaccessible
 */
public final class QueryRootTypeInaccessibleRule implements ValidationRule {
    
    private static final String CODE = "QUERY_ROOT_TYPE_INACCESSIBLE";
    
    @Override
    public ValidationPhase phase() {
        return ValidationPhase.SOURCE_SCHEMA;
    }
    
    @Override
    public String name() {
        return "QueryRootTypeInaccessibleRule";
    }
    
    @Override
    public ValidationResult validate(List<Subgraph> subgraphs) {
        ValidationResult.Builder builder = ValidationResult.builder();
        
        for (Subgraph subgraph : subgraphs) {
            validateSubgraph(subgraph, builder);
        }
        
        return builder.build();
    }
    
    private void validateSubgraph(Subgraph subgraph, ValidationResult.Builder builder) {
        GraphQLSchema schema = subgraph.schema();
        GraphQLObjectType queryType = schema.getQueryType();
        
        if (queryType != null && queryType.hasAppliedDirective(INACCESSIBLE)) {
            String message = String.format(
                "The Query root type in schema '%s' must not be marked as @inaccessible.",
                subgraph.name()
            );
            builder.addError(CODE, message, Constants.QUERY, subgraph.name(), INACCESSIBLE);
        }
    }
}
