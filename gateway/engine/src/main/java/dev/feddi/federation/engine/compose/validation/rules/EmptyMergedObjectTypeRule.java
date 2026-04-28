package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.PostMergeValidationRule;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import graphql.introspection.Introspection;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.ScalarInfo;

import java.util.List;

import static dev.feddi.federation.engine.compose.FederationDirectives.INACCESSIBLE;
import static dev.feddi.federation.engine.compose.FederationDirectives.INTERNAL;

/**
 * Validates that object types in the merged schema have at least one accessible field.
 * 
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Empty-Merged-Object-Type
 */
public final class EmptyMergedObjectTypeRule implements PostMergeValidationRule {
    
    private static final String CODE = "EMPTY_MERGED_OBJECT_TYPE";
    
    @Override
    public String name() {
        return "EmptyMergedObjectTypeRule";
    }
    
    @Override
    public ValidationResult validate(GraphQLSchema mergedSchema, List<Subgraph> subgraphs) {
        ValidationResult.Builder builder = ValidationResult.builder();
        
        for (GraphQLNamedType type : mergedSchema.getAllTypesAsList()) {
            if (type instanceof GraphQLObjectType objectType && !isBuiltInType(objectType.getName())) {
                // Skip types marked as @inaccessible (they're removed from the final schema)
                if (objectType.hasAppliedDirective(INACCESSIBLE)) {
                    continue;
                }
                
                // Check if the type has any accessible fields
                List<GraphQLFieldDefinition> accessibleFields = objectType.getFieldDefinitions().stream()
                    .filter(field -> !field.hasAppliedDirective(INACCESSIBLE))
                    .filter(field -> !field.hasAppliedDirective(INTERNAL))
                    .toList();
                
                if (accessibleFields.isEmpty()) {
                    String message = String.format(
                        "The object type '%s' has no accessible fields after merging. " +
                        "All fields are either marked as @inaccessible or @internal.",
                        objectType.getName()
                    );
                    builder.addError(CODE, message, objectType.getName(), null, null);
                }
            }
        }
        
        return builder.build();
    }
    
    private boolean isBuiltInType(String typeName) {
        return Introspection.isIntrospectionTypes(typeName) || ScalarInfo.isGraphqlSpecifiedScalar(typeName);
    }
}
