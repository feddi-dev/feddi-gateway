package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;

import java.util.List;

import static dev.feddi.federation.engine.compose.FederationDirectives.EXTERNAL;
import static dev.feddi.federation.engine.compose.validation.SchemaElements.isBuiltInType;

/**
 * Validates that @external fields do not have arguments.
 *
 * <h2>Spec Note</h2>
 * Not a spec rule. An @external field serves a @key or a @provides (EXTERNAL_UNUSED), and both must select
 * fields without arguments (KEY_FIELDS_HAS_ARGUMENTS, PROVIDES_FIELDS_HAS_ARGUMENTS), so the spec rejects
 * such fields as well, apart from one whose arguments are all @require arguments, which @provides allows.
 * This rule says so directly; it also lets composition ignore @external definitions when it merges a
 * field's arguments.
 */
public final class ExternalFieldHasArgumentsRule implements ValidationRule {

    private static final String CODE = "EXTERNAL_FIELD_HAS_ARGUMENTS";

    @Override
    public ValidationPhase phase() {
        return ValidationPhase.SOURCE_SCHEMA;
    }

    @Override
    public String name() {
        return "ExternalFieldHasArgumentsRule";
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
        String schemaName = subgraph.name();

        for (GraphQLNamedType type : schema.getAllTypesAsList()) {
            if (isBuiltInType(type.getName())) {
                continue;
            }

            if (type instanceof GraphQLObjectType objectType) {
                validateFields(objectType, schemaName, builder);
            } else if (type instanceof GraphQLInterfaceType interfaceType) {
                validateFields(interfaceType, schemaName, builder);
            }
        }
    }

    private void validateFields(GraphQLFieldsContainer type, String schemaName,
                                ValidationResult.Builder builder) {
        String typeName = ((GraphQLNamedType) type).getName();

        for (GraphQLFieldDefinition field : type.getFieldDefinitions()) {
            if (field.hasAppliedDirective(EXTERNAL) && !field.getArguments().isEmpty()) {
                String coordinate = typeName + "." + field.getName();
                String message = String.format(
                    "Field '%s' in schema '%s' is marked @external but has arguments. " +
                    "@external fields cannot have arguments because they must be usable in @provides, " +
                    "and @provides cannot reference fields with arguments.",
                    coordinate, schemaName
                );
                builder.addError(CODE, message, coordinate, schemaName, EXTERNAL);
            }
        }
    }
}
