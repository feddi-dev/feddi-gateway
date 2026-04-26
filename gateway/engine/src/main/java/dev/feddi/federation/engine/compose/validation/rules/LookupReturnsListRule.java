package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLSchema;

import java.util.List;

import static dev.feddi.federation.engine.compose.FederationDirectives.LOOKUP;

/**
 * Validates that @lookup fields do not return list types.
 * 
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Lookup-Returns-List
 */
public final class LookupReturnsListRule implements ValidationRule {
    
    private static final String CODE = "LOOKUP_RETURNS_LIST";
    
    @Override
    public ValidationPhase phase() {
        return ValidationPhase.SOURCE_SCHEMA;
    }
    
    @Override
    public String name() {
        return "LookupReturnsListRule";
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
                if (isList(field.getType())) {
                    String coordinate = String.format("%s.%s", type.getName(), field.getName());
                    String message = String.format(
                        "The @lookup field '%s' in schema '%s' must not return a list type.",
                        coordinate, schemaName
                    );
                    builder.addError(CODE, message, coordinate, schemaName, LOOKUP);
                }
            }
        }
    }
    
    private boolean isList(GraphQLOutputType type) {
        if (type instanceof GraphQLList) {
            return true;
        }
        if (type instanceof GraphQLNonNull nonNull) {
            return isList((GraphQLOutputType) nonNull.getWrappedType());
        }
        return false;
    }
}
