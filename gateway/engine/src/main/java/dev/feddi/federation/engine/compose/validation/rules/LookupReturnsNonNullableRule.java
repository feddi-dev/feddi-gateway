package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;

import java.util.List;

import static dev.feddi.federation.engine.compose.FederationDirectives.LOOKUP;

/**
 * Validates that @lookup fields return nullable types.
 * Lookup fields should return null when an entity is not found.
 * 
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Lookup-Returns-Non-Nullable-Type
 */
public final class LookupReturnsNonNullableRule implements ValidationRule {
    
    private static final String CODE = "LOOKUP_RETURNS_NON_NULLABLE_TYPE";
    
    @Override
    public ValidationPhase phase() {
        return ValidationPhase.SOURCE_SCHEMA;
    }
    
    @Override
    public String name() {
        return "LookupReturnsNonNullableRule";
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
            if (field.hasAppliedDirective(LOOKUP)) {
                if (field.getType() instanceof GraphQLNonNull) {
                    String coordinate = String.format("%s.%s", type.getName(), field.getName());
                    String message = String.format(
                        "The @lookup field '%s' in schema '%s' should return a nullable type.",
                        coordinate, schemaName
                    );
                    builder.addError(CODE, message, coordinate, schemaName, LOOKUP);
                }
            }
        }
    }
}
