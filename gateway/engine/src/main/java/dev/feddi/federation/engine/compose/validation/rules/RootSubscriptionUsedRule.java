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
 * Validates that if a root subscription type is defined, it must be named "Subscription".
 * Also validates that if a type named "Subscription" exists, it must be the root subscription type.
 *
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Root-Subscription-Used
 */
public final class RootSubscriptionUsedRule implements ValidationRule {

    private static final String CODE = "ROOT_SUBSCRIPTION_USED";

    @Override
    public ValidationPhase phase() {
        return ValidationPhase.SOURCE_SCHEMA;
    }

    @Override
    public String name() {
        return "RootSubscriptionUsedRule";
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
        GraphQLObjectType subscriptionType = schema.getSubscriptionType();
        GraphQLObjectType namedSubscriptionType = schema.getObjectType(Constants.SUBSCRIPTION);

        if (subscriptionType != null) {
            // If root subscription is defined, it must be named "Subscription"
            if (!Constants.SUBSCRIPTION.equals(subscriptionType.getName())) {
                String message = String.format(
                    "The root subscription type in schema '%s' must be named 'Subscription', but was '%s'.",
                    subgraph.name(), subscriptionType.getName()
                );
                builder.addError(CODE, message, subscriptionType.getName(), subgraph.name());
            }
        } else if (namedSubscriptionType != null) {
            // If no root subscription but a type named "Subscription" exists, that's invalid
            String message = String.format(
                "Schema '%s' defines a type named 'Subscription' but it is not the root subscription type.",
                subgraph.name()
            );
            builder.addError(CODE, message, Constants.SUBSCRIPTION, subgraph.name());
        }
    }
}
