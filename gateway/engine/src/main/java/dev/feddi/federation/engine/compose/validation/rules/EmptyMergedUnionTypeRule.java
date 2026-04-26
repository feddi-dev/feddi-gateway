package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.PostMergeValidationRule;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import graphql.schema.GraphQLDirectiveContainer;
import graphql.schema.GraphQLNamedOutputType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLUnionType;

import java.util.List;

import static dev.feddi.federation.engine.compose.FederationDirectives.INACCESSIBLE;

/**
 * Validates that union types in the merged schema have at least one accessible member.
 * 
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Empty-Merged-Union-Type
 */
public final class EmptyMergedUnionTypeRule implements PostMergeValidationRule {
    
    private static final String CODE = "EMPTY_MERGED_UNION_TYPE";
    
    @Override
    public String name() {
        return "EmptyMergedUnionTypeRule";
    }
    
    @Override
    public ValidationResult validate(GraphQLSchema mergedSchema, List<Subgraph> subgraphs) {
        ValidationResult.Builder builder = ValidationResult.builder();
        
        for (GraphQLNamedType type : mergedSchema.getAllTypesAsList()) {
            if (type instanceof GraphQLUnionType unionType) {
                // Skip types marked as @inaccessible
                if (unionType.hasAppliedDirective(INACCESSIBLE)) {
                    continue;
                }
                
                // Check if the union has any accessible member types
                List<GraphQLNamedOutputType> accessibleMembers = unionType.getTypes().stream()
                    .filter(member -> !isInaccessible(member))
                    .toList();
                
                if (accessibleMembers.isEmpty()) {
                    String message = String.format(
                        "The union type '%s' has no accessible member types after merging. " +
                        "All member types are marked as @inaccessible.",
                        unionType.getName()
                    );
                    builder.addError(CODE, message, unionType.getName(), null, null);
                }
            }
        }
        
        return builder.build();
    }
    
    private boolean isInaccessible(GraphQLNamedOutputType type) {
        if (type instanceof GraphQLDirectiveContainer container) {
            return container.hasAppliedDirective(INACCESSIBLE);
        }
        return false;
    }
}
