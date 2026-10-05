package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;

import java.util.List;

import static dev.feddi.federation.engine.compose.FederationDirectives.LOOKUP;

/**
 * Validates that @lookup fields declare at least one argument. A lookup identifies an entity by
 * its arguments; without arguments it has no stable key to resolve an entity with.
 *
 * Spec: https://graphql.github.io/graphql-federation-spec/draft/#sec-Lookup-Must-Have-Arguments
 */
public final class LookupMustHaveArgumentsRule implements ValidationRule {

    private static final String CODE = "LOOKUP_MUST_HAVE_ARGUMENTS";

    @Override
    public ValidationPhase phase() {
        return ValidationPhase.SOURCE_SCHEMA;
    }

    @Override
    public String name() {
        return "LookupMustHaveArgumentsRule";
    }

    @Override
    public ValidationResult validate(List<Subgraph> subgraphs) {
        ValidationResult.Builder builder = ValidationResult.builder();
        for (Subgraph subgraph : subgraphs) {
            for (GraphQLNamedType type : subgraph.schema().getAllTypesAsList()) {
                if (type instanceof GraphQLObjectType objectType) {
                    validateObjectType(objectType, subgraph.name(), builder);
                }
            }
        }
        return builder.build();
    }

    private void validateObjectType(GraphQLObjectType type, String schemaName, ValidationResult.Builder builder) {
        for (GraphQLFieldDefinition field : type.getFieldDefinitions()) {
            if (field.hasAppliedDirective(LOOKUP) && field.getArguments().isEmpty()) {
                String coordinate = String.format("%s.%s", type.getName(), field.getName());
                String message = String.format(
                    "The @lookup field '%s' in schema '%s' must declare at least one argument.",
                    coordinate, schemaName
                );
                builder.addError(CODE, message, coordinate, schemaName, LOOKUP);
            }
        }
    }
}
