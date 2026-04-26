package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLEnumValueDefinition;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLUnionType;

import java.util.List;

import static dev.feddi.federation.engine.compose.FederationDirectives.SHAREABLE;

/**
 * Validates that @shareable is only applied to object types and their fields.
 *
 * Per the GraphQL Composite Schemas Specification, @shareable is valid on:
 * - Object type definitions (marks all fields as shareable)
 * - Object type field definitions (except Subscription fields)
 *
 * It is NOT valid on:
 * - Interface types or their fields
 * - Subscription root type fields (subscription events cannot be shared)
 * - Input object types or their fields
 * - Union type definitions
 * - Enum types or their values
 * - Scalar type definitions
 *
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Invalid-Shareable-Usage
 */
public final class InvalidShareableUsageRule implements ValidationRule {

    private static final String CODE = "INVALID_SHAREABLE_USAGE";

    @Override
    public ValidationPhase phase() {
        return ValidationPhase.SOURCE_SCHEMA;
    }

    @Override
    public String name() {
        return "InvalidShareableUsageRule";
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

        // Check Subscription root type fields - @shareable is not allowed
        GraphQLObjectType subscriptionType = schema.getSubscriptionType();
        if (subscriptionType != null) {
            validateSubscriptionType(subscriptionType, schemaName, builder);
        }

        for (GraphQLNamedType type : schema.getAllTypesAsList()) {
            // Skip built-in types
            if (type.getName().startsWith("__")) {
                continue;
            }

            if (type instanceof GraphQLInterfaceType interfaceType) {
                validateInterfaceType(interfaceType, schemaName, builder);
            } else if (type instanceof GraphQLInputObjectType inputType) {
                validateInputObjectType(inputType, schemaName, builder);
            } else if (type instanceof GraphQLEnumType enumType) {
                validateEnumType(enumType, schemaName, builder);
            } else if (type instanceof GraphQLUnionType unionType) {
                validateUnionType(unionType, schemaName, builder);
            } else if (type instanceof GraphQLScalarType scalarType) {
                validateScalarType(scalarType, schemaName, builder);
            }
            // Note: @shareable on GraphQLObjectType definitions is valid - it marks all fields as shareable
        }
    }

    private void validateSubscriptionType(GraphQLObjectType type, String schemaName,
                                          ValidationResult.Builder builder) {
        // Subscription fields cannot be shared - subscription events from multiple schemas would conflict
        for (GraphQLFieldDefinition field : type.getFieldDefinitions()) {
            if (field.hasAppliedDirective(SHAREABLE)) {
                String coordinate = type.getName() + "." + field.getName();
                String message = String.format(
                    "The @shareable directive on subscription field '%s' in schema '%s' is invalid. " +
                    "Subscription fields cannot be shared across schemas.",
                    coordinate, schemaName
                );
                builder.addError(CODE, message, coordinate, schemaName, SHAREABLE);
            }
        }
    }

    private void validateInterfaceType(GraphQLInterfaceType type, String schemaName,
                                       ValidationResult.Builder builder) {
        // Check @shareable on interface type definition
        if (type.hasAppliedDirective(SHAREABLE)) {
            String message = String.format(
                "The @shareable directive on interface type '%s' in schema '%s' is invalid. " +
                "@shareable can only be applied to object types and their fields.",
                type.getName(), schemaName
            );
            builder.addError(CODE, message, type.getName(), schemaName, SHAREABLE);
        }

        // Check @shareable on interface fields
        for (GraphQLFieldDefinition field : type.getFieldDefinitions()) {
            if (field.hasAppliedDirective(SHAREABLE)) {
                String coordinate = type.getName() + "." + field.getName();
                String message = String.format(
                    "The @shareable directive on interface field '%s' in schema '%s' is invalid. " +
                    "@shareable can only be applied to object type field definitions.",
                    coordinate, schemaName
                );
                builder.addError(CODE, message, coordinate, schemaName, SHAREABLE);
            }
        }
    }

    private void validateInputObjectType(GraphQLInputObjectType type, String schemaName,
                                         ValidationResult.Builder builder) {
        // Check @shareable on input type definition
        if (type.hasAppliedDirective(SHAREABLE)) {
            String message = String.format(
                "The @shareable directive on input type '%s' in schema '%s' is invalid. " +
                "@shareable can only be applied to object types and their fields.",
                type.getName(), schemaName
            );
            builder.addError(CODE, message, type.getName(), schemaName, SHAREABLE);
        }

        // Check @shareable on input fields
        for (GraphQLInputObjectField field : type.getFieldDefinitions()) {
            if (field.hasAppliedDirective(SHAREABLE)) {
                String coordinate = type.getName() + "." + field.getName();
                String message = String.format(
                    "The @shareable directive on input field '%s' in schema '%s' is invalid. " +
                    "@shareable can only be applied to object type field definitions.",
                    coordinate, schemaName
                );
                builder.addError(CODE, message, coordinate, schemaName, SHAREABLE);
            }
        }
    }

    private void validateEnumType(GraphQLEnumType type, String schemaName,
                                  ValidationResult.Builder builder) {
        // Check @shareable on enum type definition
        if (type.hasAppliedDirective(SHAREABLE)) {
            String message = String.format(
                "The @shareable directive on enum type '%s' in schema '%s' is invalid. " +
                "@shareable can only be applied to object types and their fields.",
                type.getName(), schemaName
            );
            builder.addError(CODE, message, type.getName(), schemaName, SHAREABLE);
        }

        // Check @shareable on enum values
        for (GraphQLEnumValueDefinition value : type.getValues()) {
            if (value.hasAppliedDirective(SHAREABLE)) {
                String coordinate = type.getName() + "." + value.getName();
                String message = String.format(
                    "The @shareable directive on enum value '%s' in schema '%s' is invalid. " +
                    "@shareable can only be applied to object type field definitions.",
                    coordinate, schemaName
                );
                builder.addError(CODE, message, coordinate, schemaName, SHAREABLE);
            }
        }
    }

    private void validateUnionType(GraphQLUnionType type, String schemaName,
                                   ValidationResult.Builder builder) {
        // Check @shareable on union type definition
        if (type.hasAppliedDirective(SHAREABLE)) {
            String message = String.format(
                "The @shareable directive on union type '%s' in schema '%s' is invalid. " +
                "@shareable can only be applied to object types and their fields.",
                type.getName(), schemaName
            );
            builder.addError(CODE, message, type.getName(), schemaName, SHAREABLE);
        }
    }

    private void validateScalarType(GraphQLScalarType type, String schemaName,
                                    ValidationResult.Builder builder) {
        // Check @shareable on scalar type definition
        if (type.hasAppliedDirective(SHAREABLE)) {
            String message = String.format(
                "The @shareable directive on scalar type '%s' in schema '%s' is invalid. " +
                "@shareable can only be applied to object types and their fields.",
                type.getName(), schemaName
            );
            builder.addError(CODE, message, type.getName(), schemaName, SHAREABLE);
        }
    }
}
