package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.Constants;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;

import java.util.List;

/**
 * Validates that if a root mutation type is defined, it must be named "Mutation".
 * Also validates that if a type named "Mutation" exists, it must be the root mutation type.
 *
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Root-Mutation-Used
 */
public final class RootMutationUsedRule implements ValidationRule {

    private static final String CODE = "ROOT_MUTATION_USED";

    @Override
    public ValidationPhase phase() {
        return ValidationPhase.SOURCE_SCHEMA;
    }

    @Override
    public String name() {
        return "RootMutationUsedRule";
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
        GraphQLObjectType mutationType = schema.getMutationType();
        GraphQLObjectType namedMutationType = schema.getObjectType(Constants.MUTATION);

        if (mutationType != null) {
            // If root mutation is defined, it must be named "Mutation"
            if (!Constants.MUTATION.equals(mutationType.getName())) {
                String message = String.format(
                    "The root mutation type in schema '%s' must be named 'Mutation', but was '%s'.",
                    subgraph.name(), mutationType.getName()
                );
                builder.addError(CODE, message, mutationType.getName(), subgraph.name());
            }
        } else if (namedMutationType != null) {
            // If no root mutation but a type named "Mutation" exists, that's invalid
            String message = String.format(
                "Schema '%s' defines a type named 'Mutation' but it is not the root mutation type.",
                subgraph.name()
            );
            builder.addError(CODE, message, Constants.MUTATION, subgraph.name());
        }
    }
}
