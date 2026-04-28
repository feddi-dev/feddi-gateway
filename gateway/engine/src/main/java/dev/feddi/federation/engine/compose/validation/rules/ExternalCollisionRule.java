package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;

import java.util.List;

import static dev.feddi.federation.engine.compose.FederationDirectives.EXTERNAL;
import static dev.feddi.federation.engine.compose.FederationDirectives.OVERRIDE;
import static dev.feddi.federation.engine.compose.FederationDirectives.PROVIDES;
import static dev.feddi.federation.engine.compose.FederationDirectives.REQUIRE;

/**
 * Validates that fields marked with @external do not also have @override, @provides,
 * or @require on their arguments.
 *
 * Spec:
 * - EXTERNAL_OVERRIDE_COLLISION: @external + @override not allowed
 * - EXTERNAL_PROVIDES_COLLISION: @external + @provides not allowed
 * - EXTERNAL_REQUIRE_COLLISION: @external + @require on argument not allowed
 */
public final class ExternalCollisionRule implements ValidationRule {

    private static final String EXTERNAL_OVERRIDE_COLLISION = "EXTERNAL_OVERRIDE_COLLISION";
    private static final String EXTERNAL_PROVIDES_COLLISION = "EXTERNAL_PROVIDES_COLLISION";
    private static final String EXTERNAL_REQUIRE_COLLISION = "EXTERNAL_REQUIRE_COLLISION";

    @Override
    public ValidationPhase phase() {
        return ValidationPhase.SOURCE_SCHEMA;
    }

    @Override
    public String name() {
        return "ExternalCollisionRule";
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
            if (type instanceof GraphQLObjectType objType) {
                validateObjectType(objType, subgraph.name(), builder);
            } else if (type instanceof GraphQLInterfaceType ifaceType) {
                validateInterfaceType(ifaceType, subgraph.name(), builder);
            }
        }
    }

    private void validateObjectType(GraphQLObjectType type, String schemaName, ValidationResult.Builder builder) {
        for (GraphQLFieldDefinition field : type.getFieldDefinitions()) {
            validateField(type.getName(), field, schemaName, builder);
        }
    }

    private void validateInterfaceType(GraphQLInterfaceType type, String schemaName, ValidationResult.Builder builder) {
        for (GraphQLFieldDefinition field : type.getFieldDefinitions()) {
            validateField(type.getName(), field, schemaName, builder);
        }
    }

    private void validateField(String typeName, GraphQLFieldDefinition field, String schemaName, ValidationResult.Builder builder) {
        boolean isExternal = field.hasAppliedDirective(EXTERNAL);

        if (!isExternal) {
            return;
        }

        String coordinate = typeName + "." + field.getName();

        // Check @external + @override collision
        if (field.hasAppliedDirective(OVERRIDE)) {
            String message = String.format(
                "Field '%s' in schema '%s' cannot have both @external and @override directives.",
                coordinate, schemaName
            );
            builder.addError(EXTERNAL_OVERRIDE_COLLISION, message, coordinate, schemaName);
        }

        // Check @external + @provides collision
        if (field.hasAppliedDirective(PROVIDES)) {
            String message = String.format(
                "Field '%s' in schema '%s' cannot have both @external and @provides directives.",
                coordinate, schemaName
            );
            builder.addError(EXTERNAL_PROVIDES_COLLISION, message, coordinate, schemaName);
        }

        // Check @external + @require collision on arguments
        for (GraphQLArgument arg : field.getArguments()) {
            if (arg.hasAppliedDirective(REQUIRE)) {
                String message = String.format(
                    "Argument '%s' on external field '%s' in schema '%s' cannot have @require directive.",
                    arg.getName(), coordinate, schemaName
                );
                builder.addError(EXTERNAL_REQUIRE_COLLISION, message, coordinate + "." + arg.getName(), schemaName);
            }
        }
    }
}
