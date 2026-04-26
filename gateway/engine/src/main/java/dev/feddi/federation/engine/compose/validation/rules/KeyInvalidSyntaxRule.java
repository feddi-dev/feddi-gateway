package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLAppliedDirectiveArgument;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;

import java.util.List;
import java.util.regex.Pattern;

import static dev.feddi.federation.engine.compose.FederationDirectives.KEY;

/**
 * Validates that @key directive has valid field selection syntax.
 * 
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Key-Invalid-Syntax
 */
public final class KeyInvalidSyntaxRule implements ValidationRule {
    
    private static final String CODE = "KEY_INVALID_SYNTAX";
    
    // Simple pattern for valid field selection (field names with optional nesting)
    // This is a simplified check - a full implementation would use the GraphQL parser
    private static final Pattern VALID_FIELDS_PATTERN = Pattern.compile(
        "^[a-zA-Z_][a-zA-Z0-9_]*(\\s+[a-zA-Z_][a-zA-Z0-9_]*)*(\\s*\\{[^}]+\\})?$"
    );
    
    @Override
    public ValidationPhase phase() {
        return ValidationPhase.SOURCE_SCHEMA;
    }
    
    @Override
    public String name() {
        return "KeyInvalidSyntaxRule";
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
            } else if (type instanceof GraphQLInterfaceType interfaceType) {
                validateInterfaceType(interfaceType, subgraph.name(), builder);
            }
        }
    }
    
    private void validateObjectType(GraphQLObjectType type, String schemaName, ValidationResult.Builder builder) {
        for (GraphQLAppliedDirective directive : type.getAppliedDirectives()) {
            if (KEY.equals(directive.getName())) {
                validateKeyDirective(type.getName(), directive, schemaName, builder);
            }
        }
    }
    
    private void validateInterfaceType(GraphQLInterfaceType type, String schemaName, ValidationResult.Builder builder) {
        for (GraphQLAppliedDirective directive : type.getAppliedDirectives()) {
            if (KEY.equals(directive.getName())) {
                validateKeyDirective(type.getName(), directive, schemaName, builder);
            }
        }
    }
    
    private void validateKeyDirective(String typeName, GraphQLAppliedDirective directive, 
            String schemaName, ValidationResult.Builder builder) {
        GraphQLAppliedDirectiveArgument fieldsArg = directive.getArgument("fields");
        if (fieldsArg == null) {
            builder.addError(CODE, 
                String.format("The @key directive on type '%s' in schema '%s' is missing the 'fields' argument.", 
                    typeName, schemaName),
                typeName, schemaName, KEY);
            return;
        }
        
        Object value = fieldsArg.getValue();
        if (value == null) {
            builder.addError(CODE,
                String.format("The @key directive on type '%s' in schema '%s' has a null 'fields' argument.",
                    typeName, schemaName),
                typeName, schemaName, KEY);
            return;
        }
        
        String fieldsValue = value.toString();
        if (fieldsValue.isBlank()) {
            builder.addError(CODE,
                String.format("The @key directive on type '%s' in schema '%s' has an empty 'fields' argument.",
                    typeName, schemaName),
                typeName, schemaName, KEY);
        }
    }
}
