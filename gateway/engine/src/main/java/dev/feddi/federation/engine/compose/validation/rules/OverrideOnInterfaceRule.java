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

import static dev.feddi.federation.engine.compose.FederationDirectives.OVERRIDE;

/**
 * Validates that @override is not used on interface fields.
 *
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Override-on-Interface
 */
public final class OverrideOnInterfaceRule implements ValidationRule {

    private static final String CODE = "OVERRIDE_ON_INTERFACE";

    @Override
    public ValidationPhase phase() {
        return ValidationPhase.SOURCE_SCHEMA;
    }

    @Override
    public String name() {
        return "OverrideOnInterfaceRule";
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
        String schemaName = subgraph.name();
        GraphQLSchema schema = subgraph.schema();

        for (GraphQLType type : schema.getAllTypesAsList()) {
            if (type instanceof GraphQLInterfaceType ifaceType) {
                validateInterface(ifaceType, schemaName, builder);
            }
        }
    }

    private void validateInterface(GraphQLInterfaceType iface, String schemaName, ValidationResult.Builder builder) {
        String typeName = iface.getName();

        for (GraphQLFieldDefinition field : iface.getFieldDefinitions()) {
            if (field.hasAppliedDirective(OVERRIDE)) {
                String coordinate = typeName + "." + field.getName();
                String message = String.format(
                    "Field '%s' in schema '%s' uses @override on an interface field. @override cannot be used on interface fields.",
                    coordinate, schemaName
                );
                builder.addError(CODE, message, coordinate, schemaName);
            }
        }
    }
}
