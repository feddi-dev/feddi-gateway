package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;

import java.util.List;

import static dev.feddi.federation.engine.compose.FederationDirectives.IS;
import static dev.feddi.federation.engine.compose.FederationDirectives.LOOKUP;

/**
 * Validates that @is directive is only used on arguments of @lookup fields.
 * 
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-is
 */
public final class IsInvalidUsageRule implements ValidationRule {
    
    private static final String CODE = "IS_INVALID_USAGE";
    
    @Override
    public ValidationPhase phase() {
        return ValidationPhase.SOURCE_SCHEMA;
    }
    
    @Override
    public String name() {
        return "IsInvalidUsageRule";
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
        
        for (GraphQLNamedType type : schema.getAllTypesAsList()) {
            if (type instanceof GraphQLObjectType objectType) {
                validateObjectType(objectType, subgraph.name(), builder);
            }
        }
    }
    
    private void validateObjectType(GraphQLObjectType type, String schemaName, ValidationResult.Builder builder) {
        for (GraphQLFieldDefinition field : type.getFieldDefinitions()) {
            boolean hasLookup = field.hasAppliedDirective(LOOKUP);
            
            for (GraphQLArgument arg : field.getArguments()) {
                if (arg.hasAppliedDirective(IS) && !hasLookup) {
                    String coordinate = String.format("%s.%s(%s:)", type.getName(), field.getName(), arg.getName());
                    String message = String.format(
                        "The @is directive on argument '%s' in schema '%s' is invalid because the declaring field is not a lookup field.",
                        coordinate, schemaName.toUpperCase()
                    );
                    builder.addError(CODE, message, coordinate, schemaName, IS);
                }
            }
        }
    }
}
