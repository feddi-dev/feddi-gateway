package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import graphql.schema.GraphQLDirectiveContainer;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLTypeUtil;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static dev.feddi.federation.engine.compose.FederationDirectives.INTERNAL;

/**
 * Validates that the composite schema's fields do not reference types that only exist as {@code @internal}
 * types: a field that is not {@code @internal} itself must not return a type that no schema defines publicly.
 *
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Reference-To-Internal-Type
 */
public final class ReferenceToInternalTypeRule implements ValidationRule {

    private static final String CODE = "REFERENCE_TO_INTERNAL_TYPE";

    @Override
    public ValidationPhase phase() {
        return ValidationPhase.PRE_MERGE;
    }

    @Override
    public String name() {
        return "ReferenceToInternalTypeRule";
    }

    @Override
    public ValidationResult validate(List<Subgraph> subgraphs) {
        Set<String> publicTypes = new HashSet<>();
        for (Subgraph subgraph : subgraphs) {
            for (GraphQLNamedType type : subgraph.schema().getAllTypesAsList()) {
                if (!isInternal(type)) {
                    publicTypes.add(type.getName());
                }
            }
        }

        ValidationResult.Builder builder = ValidationResult.builder();
        for (Subgraph subgraph : subgraphs) {
            for (GraphQLNamedType type : subgraph.schema().getAllTypesAsList()) {
                if (!(type instanceof GraphQLFieldsContainer container) || isInternal(type)) {
                    continue;
                }
                for (GraphQLFieldDefinition field : container.getFieldDefinitions()) {
                    String fieldType = GraphQLTypeUtil.unwrapAll(field.getType()).getName();
                    if (!field.hasAppliedDirective(INTERNAL) && !publicTypes.contains(fieldType)) {
                        String coordinate = container.getName() + "." + field.getName();
                        builder.addError(CODE, String.format(
                            "Field '%s' in schema '%s' returns '%s', which is @internal in every schema that defines "
                                + "it; mark the field @internal as well or make the type public.",
                            coordinate, subgraph.name(), fieldType), coordinate, subgraph.name());
                    }
                }
            }
        }
        return builder.build();
    }

    private static boolean isInternal(GraphQLNamedType type) {
        return type instanceof GraphQLDirectiveContainer container
            && container.hasAppliedDirective(INTERNAL);
    }
}
