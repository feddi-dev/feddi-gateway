package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.PostMergeValidationRule;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLSchema;

import java.util.List;

import static dev.feddi.federation.engine.compose.FederationDirectives.INACCESSIBLE;

/**
 * Validates that input object types in the merged schema have at least one accessible field.
 * 
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Empty-Merged-Input-Object-Type
 */
public final class EmptyMergedInputObjectTypeRule implements PostMergeValidationRule {
    
    private static final String CODE = "EMPTY_MERGED_INPUT_OBJECT_TYPE";
    
    @Override
    public String name() {
        return "EmptyMergedInputObjectTypeRule";
    }
    
    @Override
    public ValidationResult validate(GraphQLSchema mergedSchema, List<Subgraph> subgraphs) {
        ValidationResult.Builder builder = ValidationResult.builder();
        
        for (GraphQLNamedType type : mergedSchema.getAllTypesAsList()) {
            if (type instanceof GraphQLInputObjectType inputType) {
                // Skip types marked as @inaccessible
                if (inputType.hasAppliedDirective(INACCESSIBLE)) {
                    continue;
                }
                
                // Check if the input type has any accessible fields
                List<GraphQLInputObjectField> accessibleFields = inputType.getFieldDefinitions().stream()
                    .filter(field -> !field.hasAppliedDirective(INACCESSIBLE))
                    .toList();
                
                if (accessibleFields.isEmpty()) {
                    String message = String.format(
                        "The input object type '%s' has no accessible fields after merging. " +
                        "All fields are marked as @inaccessible.",
                        inputType.getName()
                    );
                    builder.addError(CODE, message, inputType.getName(), null, null);
                }
            }
        }
        
        return builder.build();
    }
}
