package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;

import java.util.List;

import static dev.feddi.federation.engine.compose.FederationDirectives.EXTERNAL;

/**
 * Validates that @external is not used on interface fields.
 * Interface fields are abstract and don't have direct resolutions,
 * so marking them as @external is nonsensical.
 *
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-External-on-Interface
 */
public final class ExternalOnInterfaceRule implements ValidationRule {

    private static final String CODE = "EXTERNAL_ON_INTERFACE";

    @Override
    public ValidationPhase phase() {
        return ValidationPhase.SOURCE_SCHEMA;
    }

    @Override
    public String name() {
        return "ExternalOnInterfaceRule";
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

        for (GraphQLType type : schema.getAllTypesAsList()) {
            if (type instanceof GraphQLInterfaceType ifaceType) {
                validateInterfaceType(ifaceType, subgraph.name(), builder);
            }
        }
    }

    private void validateInterfaceType(GraphQLInterfaceType type, String schemaName, ValidationResult.Builder builder) {
        for (GraphQLFieldDefinition field : type.getFieldDefinitions()) {
            if (field.hasAppliedDirective(EXTERNAL)) {
                String coordinate = type.getName() + "." + field.getName();
                String message = String.format(
                    "Field '%s' on interface '%s' in schema '%s' cannot be marked as @external. " +
                    "Interface fields are abstract and cannot be external.",
                    field.getName(), type.getName(), schemaName
                );
                builder.addError(CODE, message, coordinate, schemaName);
            }
        }
    }
}
