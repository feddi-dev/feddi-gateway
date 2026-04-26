package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.Constants;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.PostMergeValidationRule;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;

import java.util.List;

import static dev.feddi.federation.engine.compose.FederationDirectives.INACCESSIBLE;

/**
 * Validates that the merged schema has at least one accessible query field.
 * 
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-No-Queries
 */
public final class NoQueriesRule implements PostMergeValidationRule {
    
    private static final String CODE = "NO_QUERIES";
    
    @Override
    public String name() {
        return "NoQueriesRule";
    }
    
    @Override
    public ValidationResult validate(GraphQLSchema mergedSchema, List<Subgraph> subgraphs) {
        ValidationResult.Builder builder = ValidationResult.builder();
        
        GraphQLObjectType queryType = mergedSchema.getQueryType();
        
        if (queryType == null) {
            builder.addError(CODE, 
                "The merged schema must have a Query type with at least one accessible field.",
                Constants.QUERY, null, null);
            return builder.build();
        }
        
        // Check if Query type has any accessible fields
        List<GraphQLFieldDefinition> accessibleFields = queryType.getFieldDefinitions().stream()
            .filter(field -> !field.hasAppliedDirective(INACCESSIBLE))
            .toList();
        
        if (accessibleFields.isEmpty()) {
            builder.addError(CODE,
                "The Query type in the merged schema has no accessible fields.",
                Constants.QUERY, null, null);
        }
        
        return builder.build();
    }
}
