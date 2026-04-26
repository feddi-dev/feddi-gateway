package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import graphql.language.StringValue;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLAppliedDirectiveArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;

import java.util.List;

import static dev.feddi.federation.engine.compose.FederationDirectives.OVERRIDE;

/**
 * Validates that @override(from:) does not reference the same schema.
 *
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Override-From-Self
 */
public final class OverrideFromSelfRule implements ValidationRule {

    private static final String CODE = "OVERRIDE_FROM_SELF";

    @Override
    public ValidationPhase phase() {
        return ValidationPhase.SOURCE_SCHEMA;
    }

    @Override
    public String name() {
        return "OverrideFromSelfRule";
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
            if (type instanceof GraphQLObjectType objType) {
                validateType(objType, schemaName, builder);
            } else if (type instanceof GraphQLInterfaceType ifaceType) {
                validateType(ifaceType, schemaName, builder);
            }
        }
    }

    private void validateType(GraphQLFieldsContainer type, String schemaName, ValidationResult.Builder builder) {
        String typeName = ((GraphQLNamedType) type).getName();

        for (GraphQLFieldDefinition field : type.getFieldDefinitions()) {
            for (GraphQLAppliedDirective override : field.getAppliedDirectives(OVERRIDE)) {
                GraphQLAppliedDirectiveArgument fromArg = override.getArgument("from");
                if (fromArg == null) {
                    continue;
                }

                String fromValue = extractStringValue(fromArg.getValue());
                if (fromValue != null && fromValue.equals(schemaName)) {
                    String coordinate = typeName + "." + field.getName();
                    String message = String.format(
                        "Field '%s' in schema '%s' has @override(from: \"%s\") referencing itself.",
                        coordinate, schemaName, fromValue
                    );
                    builder.addError(CODE, message, coordinate, schemaName);
                }
            }
        }
    }

    private String extractStringValue(Object value) {
        if (value instanceof String s) {
            return s;
        } else if (value instanceof StringValue sv) {
            return sv.getValue();
        }
        return null;
    }
}
