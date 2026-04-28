package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.PostMergeValidationRule;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLSchema;

import java.util.List;

import static dev.feddi.federation.engine.compose.FederationDirectives.INACCESSIBLE;
import static dev.feddi.federation.engine.compose.FederationDirectives.INTERNAL;

/**
 * Validates that interface types in the merged schema have at least one accessible field.
 * 
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Empty-Merged-Interface-Type
 */
public final class EmptyMergedInterfaceTypeRule implements PostMergeValidationRule {
    
    private static final String CODE = "EMPTY_MERGED_INTERFACE_TYPE";
    
    @Override
    public String name() {
        return "EmptyMergedInterfaceTypeRule";
    }
    
    @Override
    public ValidationResult validate(GraphQLSchema mergedSchema, List<Subgraph> subgraphs) {
        ValidationResult.Builder builder = ValidationResult.builder();
        
        for (GraphQLNamedType type : mergedSchema.getAllTypesAsList()) {
            if (type instanceof GraphQLInterfaceType interfaceType) {
                // Skip types marked as @inaccessible
                if (interfaceType.hasAppliedDirective(INACCESSIBLE)) {
                    continue;
                }
                
                // Check if the interface has any accessible fields
                List<GraphQLFieldDefinition> accessibleFields = interfaceType.getFieldDefinitions().stream()
                    .filter(field -> !field.hasAppliedDirective(INACCESSIBLE))
                    .filter(field -> !field.hasAppliedDirective(INTERNAL))
                    .toList();
                
                if (accessibleFields.isEmpty()) {
                    String message = String.format(
                        "The interface type '%s' has no accessible fields after merging. " +
                        "All fields are either marked as @inaccessible or @internal.",
                        interfaceType.getName()
                    );
                    builder.addError(CODE, message, interfaceType.getName(), null, null);
                }
            }
        }
        
        return builder.build();
    }
}
