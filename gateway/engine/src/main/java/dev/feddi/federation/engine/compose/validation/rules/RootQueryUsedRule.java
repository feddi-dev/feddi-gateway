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
 * Validates that if a root query type is defined, it must be named "Query".
 * Also validates that if a type named "Query" exists, it must be the root query type.
 *
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Root-Query-Used
 */
public final class RootQueryUsedRule implements ValidationRule {

    private static final String CODE = "ROOT_QUERY_USED";

    @Override
    public ValidationPhase phase() {
        return ValidationPhase.SOURCE_SCHEMA;
    }

    @Override
    public String name() {
        return "RootQueryUsedRule";
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
        GraphQLObjectType queryType = schema.getQueryType();
        GraphQLObjectType namedQueryType = schema.getObjectType(Constants.QUERY);

        if (queryType != null) {
            // If root query is defined, it must be named "Query"
            if (!Constants.QUERY.equals(queryType.getName())) {
                String message = String.format(
                    "The root query type in schema '%s' must be named 'Query', but was '%s'.",
                    subgraph.name(), queryType.getName()
                );
                builder.addError(CODE, message, queryType.getName(), subgraph.name());
            }
        } else if (namedQueryType != null) {
            // If no root query but a type named "Query" exists, that's invalid
            String message = String.format(
                "Schema '%s' defines a type named 'Query' but it is not the root query type.",
                subgraph.name()
            );
            builder.addError(CODE, message, Constants.QUERY, subgraph.name());
        }
    }
}
