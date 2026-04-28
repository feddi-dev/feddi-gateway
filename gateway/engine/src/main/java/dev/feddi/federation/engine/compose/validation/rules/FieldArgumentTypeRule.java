package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import graphql.language.StringValue;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLAppliedDirectiveArgument;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;

import java.util.List;

import static dev.feddi.federation.engine.compose.FederationDirectives.IS;
import static dev.feddi.federation.engine.compose.FederationDirectives.REQUIRE;

/**
 * Validates that @is and @require directives have string 'field' arguments.
 *
 * - IS_INVALID_FIELD_TYPE: @is field argument must be a string
 * - REQUIRE_INVALID_FIELD_TYPE: @require field argument must be a string
 *
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Is-Invalid-Fields-Type
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Require-Invalid-Fields-Type
 */
public final class FieldArgumentTypeRule implements ValidationRule {

    private static final String IS_INVALID_FIELD_TYPE = "IS_INVALID_FIELD_TYPE";
    private static final String REQUIRE_INVALID_FIELD_TYPE = "REQUIRE_INVALID_FIELD_TYPE";

    @Override
    public ValidationPhase phase() {
        return ValidationPhase.SOURCE_SCHEMA;
    }

    @Override
    public String name() {
        return "FieldArgumentTypeRule";
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
            for (GraphQLArgument arg : field.getArguments()) {
                validateArgumentDirectives(arg, type.getName(), field.getName(), schemaName, builder);
            }
        }
    }

    private void validateInterfaceType(GraphQLInterfaceType type, String schemaName, ValidationResult.Builder builder) {
        for (GraphQLFieldDefinition field : type.getFieldDefinitions()) {
            for (GraphQLArgument arg : field.getArguments()) {
                validateArgumentDirectives(arg, type.getName(), field.getName(), schemaName, builder);
            }
        }
    }

    private void validateArgumentDirectives(GraphQLArgument arg, String typeName, String fieldName,
                                            String schemaName, ValidationResult.Builder builder) {
        String coordinate = String.format("%s.%s(%s:)", typeName, fieldName, arg.getName());

        // Check @is directive
        for (GraphQLAppliedDirective isDirective : arg.getAppliedDirectives(IS)) {
            GraphQLAppliedDirectiveArgument fieldArg = isDirective.getArgument("field");
            if (fieldArg != null) {
                Object value = fieldArg.getValue();
                // Value can be String or StringValue (from graphql-java's AST)
                if (!isStringValue(value)) {
                    String message = String.format(
                        "The @is directive on argument '%s' in schema '%s' has a non-string 'field' argument.",
                        coordinate, schemaName
                    );
                    builder.addError(IS_INVALID_FIELD_TYPE, message, coordinate, schemaName);
                }
            }
        }

        // Check @require directive
        for (GraphQLAppliedDirective requireDirective : arg.getAppliedDirectives(REQUIRE)) {
            GraphQLAppliedDirectiveArgument fieldArg = requireDirective.getArgument("field");
            if (fieldArg != null) {
                Object value = fieldArg.getValue();
                // Value can be String or StringValue (from graphql-java's AST)
                if (!isStringValue(value)) {
                    String message = String.format(
                        "The @require directive on argument '%s' in schema '%s' has a non-string 'field' argument.",
                        coordinate, schemaName
                    );
                    builder.addError(REQUIRE_INVALID_FIELD_TYPE, message, coordinate, schemaName);
                }
            }
        }
    }

    private boolean isStringValue(Object value) {
        return value instanceof String || value instanceof StringValue;
    }
}
