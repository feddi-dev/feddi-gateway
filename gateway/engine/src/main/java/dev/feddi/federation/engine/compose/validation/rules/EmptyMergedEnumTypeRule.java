package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.PostMergeValidationRule;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import graphql.introspection.Introspection;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLEnumValueDefinition;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLSchema;

import java.util.List;

import static dev.feddi.federation.engine.compose.FederationDirectives.INACCESSIBLE;

/**
 * Validates that enum types in the merged schema have at least one accessible value.
 * 
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Empty-Merged-Enum-Type
 */
public final class EmptyMergedEnumTypeRule implements PostMergeValidationRule {
    
    private static final String CODE = "EMPTY_MERGED_ENUM_TYPE";
    
    @Override
    public String name() {
        return "EmptyMergedEnumTypeRule";
    }
    
    @Override
    public ValidationResult validate(GraphQLSchema mergedSchema, List<Subgraph> subgraphs) {
        ValidationResult.Builder builder = ValidationResult.builder();
        
        for (GraphQLNamedType type : mergedSchema.getAllTypesAsList()) {
            if (type instanceof GraphQLEnumType enumType && !isBuiltInType(enumType.getName())) {
                // Skip types marked as @inaccessible
                if (enumType.hasAppliedDirective(INACCESSIBLE)) {
                    continue;
                }
                
                // Check if the enum has any accessible values
                List<GraphQLEnumValueDefinition> accessibleValues = enumType.getValues().stream()
                    .filter(value -> !value.hasAppliedDirective(INACCESSIBLE))
                    .toList();
                
                if (accessibleValues.isEmpty()) {
                    String message = String.format(
                        "The enum type '%s' has no accessible values after merging. " +
                        "All values are marked as @inaccessible.",
                        enumType.getName()
                    );
                    builder.addError(CODE, message, enumType.getName(), null, null);
                }
            }
        }
        
        return builder.build();
    }
    
    private boolean isBuiltInType(String typeName) {
        return Introspection.isIntrospectionTypes(typeName);
    }
}
