package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.parser.FieldSelectionMapParser;
import dev.feddi.federation.engine.parser.InvalidSyntaxException;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import graphql.introspection.Introspection;
import graphql.language.StringValue;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLAppliedDirectiveArgument;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.ScalarInfo;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.feddi.federation.engine.compose.FederationDirectives.IS;
import static dev.feddi.federation.engine.compose.FederationDirectives.KEY;
import static dev.feddi.federation.engine.compose.FederationDirectives.PROVIDES;
import static dev.feddi.federation.engine.compose.FederationDirectives.REQUIRE;

/**
 * Validates that all FieldSelectionMap and FieldSelectionSet values have valid syntax.
 *
 * - FieldSelectionSet (for @key and @provides): GraphQL selection set syntax like "id name"
 * - FieldSelectionMap (for @is and @require): Path-based syntax with type conditions
 *
 * This rule should run early in validation to ensure other rules can safely parse these values.
 */
public final class FieldSelectionMapSyntaxRule implements ValidationRule {

    private static final String KEY_CODE = "KEY_INVALID_SYNTAX";
    private static final String KEY_DIRECTIVE_CODE = "KEY_DIRECTIVE_IN_FIELDS_ARG";
    private static final String IS_CODE = "IS_INVALID_SYNTAX";
    private static final String REQUIRE_CODE = "REQUIRE_INVALID_SYNTAX";
    private static final String PROVIDES_CODE = "PROVIDES_INVALID_SYNTAX";
    private static final String PROVIDES_DIRECTIVE_CODE = "PROVIDES_DIRECTIVE_IN_FIELDS_ARG";

    // Pattern to detect directive syntax: @ followed by a valid GraphQL name
    // This matches @directiveName, @skip, @include, etc.
    private static final Pattern DIRECTIVE_PATTERN = Pattern.compile("@[_A-Za-z][_0-9A-Za-z]*");

    @Override
    public ValidationPhase phase() {
        return ValidationPhase.SOURCE_SCHEMA;
    }

    @Override
    public String name() {
        return "FieldSelectionMapSyntaxRule";
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
            if (type instanceof GraphQLObjectType objectType && !isBuiltInType(objectType.getName())) {
                validateObjectType(objectType, schemaName, builder);
            } else if (type instanceof GraphQLInterfaceType interfaceType && !isBuiltInType(interfaceType.getName())) {
                validateInterfaceType(interfaceType, schemaName, builder);
            }
        }
    }

    private void validateObjectType(GraphQLObjectType type, String schemaName, ValidationResult.Builder builder) {
        // Validate @key directives on the type
        for (GraphQLAppliedDirective directive : type.getAppliedDirectives()) {
            if (KEY.equals(directive.getName())) {
                validateKeyDirective(type.getName(), directive, schemaName, builder);
            }
        }

        // Validate @is, @require, @provides on fields
        for (GraphQLFieldDefinition field : type.getFieldDefinitions()) {
            validateField(type.getName(), field, schemaName, builder);
        }
    }

    private void validateInterfaceType(GraphQLInterfaceType type, String schemaName, ValidationResult.Builder builder) {
        // Validate @key directives on the type
        for (GraphQLAppliedDirective directive : type.getAppliedDirectives()) {
            if (KEY.equals(directive.getName())) {
                validateKeyDirective(type.getName(), directive, schemaName, builder);
            }
        }

        // Validate @is, @require, @provides on fields
        for (GraphQLFieldDefinition field : type.getFieldDefinitions()) {
            validateField(type.getName(), field, schemaName, builder);
        }
    }

    private void validateKeyDirective(String typeName, GraphQLAppliedDirective directive,
                                       String schemaName, ValidationResult.Builder builder) {
        String fieldsValue = getStringArgument(directive, "fields");
        if (fieldsValue == null || fieldsValue.isBlank()) {
            builder.addError(KEY_CODE,
                String.format("The @key directive on type '%s' in schema '%s' has missing or empty 'fields' argument.",
                    typeName, schemaName),
                typeName, schemaName, KEY);
            return;
        }

        // Check for directive syntax in the fields argument
        String directiveName = findDirectiveInFields(fieldsValue);
        if (directiveName != null) {
            builder.addError(KEY_DIRECTIVE_CODE,
                String.format("The @key directive on type '%s' in schema '%s' has an invalid 'fields' argument " +
                    "containing directive '%s'. Directives are not allowed in field selection sets.",
                    typeName, schemaName, directiveName),
                typeName, schemaName, KEY);
            return;
        }

        // @key uses FieldSelectionSet syntax (GraphQL selection set)
        validateFieldSelectionSetSyntax(fieldsValue, KEY_CODE,
            String.format("@key(fields: \"%s\") on type '%s'", fieldsValue, typeName),
            typeName, schemaName, KEY, builder);
    }

    private void validateField(String typeName, GraphQLFieldDefinition field,
                               String schemaName, ValidationResult.Builder builder) {
        String coordinate = typeName + "." + field.getName();

        // Validate @provides on field - uses FieldSelectionSet syntax
        if (field.hasAppliedDirective(PROVIDES)) {
            GraphQLAppliedDirective providesDirective = field.getAppliedDirective(PROVIDES);
            String fieldsValue = getStringArgument(providesDirective, "fields");
            if (fieldsValue != null && !fieldsValue.isBlank()) {
                // Check for directive syntax in the fields argument
                String directiveName = findDirectiveInFields(fieldsValue);
                if (directiveName != null) {
                    builder.addError(PROVIDES_DIRECTIVE_CODE,
                        String.format("The @provides directive on field '%s' in schema '%s' has an invalid 'fields' argument " +
                            "containing directive '%s'. Directives are not allowed in field selection sets.",
                            coordinate, schemaName, directiveName),
                        coordinate, schemaName, PROVIDES);
                } else {
                    validateFieldSelectionSetSyntax(fieldsValue, PROVIDES_CODE,
                        String.format("@provides(fields: \"%s\") on field '%s'", fieldsValue, coordinate),
                        coordinate, schemaName, PROVIDES, builder);
                }
            }
        }

        // Validate @is and @require on field arguments
        for (GraphQLArgument arg : field.getArguments()) {
            validateArgumentDirectives(coordinate, arg, schemaName, builder);
        }
    }

    private void validateArgumentDirectives(String fieldCoordinate, GraphQLArgument arg,
                                            String schemaName, ValidationResult.Builder builder) {
        String argCoordinate = fieldCoordinate + "(" + arg.getName() + ")";

        // Validate @is on argument - uses FieldSelectionMap syntax
        if (arg.hasAppliedDirective(IS)) {
            GraphQLAppliedDirective isDirective = arg.getAppliedDirective(IS);
            String fieldValue = getStringArgument(isDirective, "field");
            if (fieldValue != null && !fieldValue.isBlank()) {
                validateFieldSelectionMapSyntax(fieldValue, IS_CODE,
                    String.format("@is(field: \"%s\") on argument '%s'", fieldValue, argCoordinate),
                    argCoordinate, schemaName, IS, builder);
            }
        }

        // Validate @require on argument - uses FieldSelectionMap syntax
        if (arg.hasAppliedDirective(REQUIRE)) {
            GraphQLAppliedDirective requireDirective = arg.getAppliedDirective(REQUIRE);
            String fieldValue = getStringArgument(requireDirective, "field");
            if (fieldValue != null && !fieldValue.isBlank()) {
                validateFieldSelectionMapSyntax(fieldValue, REQUIRE_CODE,
                    String.format("@require(field: \"%s\") on argument '%s'", fieldValue, argCoordinate),
                    argCoordinate, schemaName, REQUIRE, builder);
            }
        }
    }

    /**
     * Validates FieldSelectionSet syntax (used by @key and @provides).
     * Supports GraphQL selection set syntax like "id name", "author { id name }".
     */
    private void validateFieldSelectionSetSyntax(String value, String errorCode, String context,
                                                  String coordinate, String schemaName, String directiveName,
                                                  ValidationResult.Builder builder) {
        try {
            FieldSelectionMapParser.parseFieldSelectionSet(value);
        } catch (InvalidSyntaxException e) {
            builder.addError(errorCode,
                String.format("Invalid FieldSelectionSet syntax in %s in schema '%s': %s",
                    context, schemaName, e.getMessage()),
                coordinate, schemaName, directiveName);
        }
    }

    /**
     * Validates FieldSelectionMap syntax (used by @is and @require).
     * Supports path-based syntax with type conditions and pipe-separated alternatives.
     */
    private void validateFieldSelectionMapSyntax(String value, String errorCode, String context,
                                                  String coordinate, String schemaName, String directiveName,
                                                  ValidationResult.Builder builder) {
        try {
            FieldSelectionMapParser.parseFieldSelectionMap(value);
        } catch (InvalidSyntaxException e) {
            builder.addError(errorCode,
                String.format("Invalid FieldSelectionMap syntax in %s in schema '%s': %s",
                    context, schemaName, e.getMessage()),
                coordinate, schemaName, directiveName);
        }
    }

    private String getStringArgument(GraphQLAppliedDirective directive, String argName) {
        GraphQLAppliedDirectiveArgument arg = directive.getArgument(argName);
        if (arg == null) {
            return null;
        }
        Object value = arg.getValue();
        if (value instanceof StringValue stringValue) {
            return stringValue.getValue();
        }
        return value != null ? value.toString() : null;
    }

    private boolean isBuiltInType(String typeName) {
        return Introspection.isIntrospectionTypes(typeName) || ScalarInfo.isGraphqlSpecifiedScalar(typeName);
    }

    /**
     * Checks if the fields value contains any directive syntax (@directiveName).
     * Returns the directive name if found, null otherwise.
     */
    private String findDirectiveInFields(String fieldsValue) {
        Matcher matcher = DIRECTIVE_PATTERN.matcher(fieldsValue);
        if (matcher.find()) {
            return matcher.group(); // Returns the matched directive (e.g., "@lowercase")
        }
        return null;
    }
}
