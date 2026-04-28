package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLDirective;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLUnionType;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.feddi.federation.engine.compose.FederationDirectives.IS;
import static dev.feddi.federation.engine.compose.FederationDirectives.KEY;
import static dev.feddi.federation.engine.compose.FederationDirectives.OVERRIDE;
import static dev.feddi.federation.engine.compose.FederationDirectives.PROVIDES;
import static dev.feddi.federation.engine.compose.FederationDirectives.REQUIRE;

/**
 * Validates that built-in types and directives conform to the composition specification.
 *
 * Built-in types like FieldSelectionMap must be scalars, not input objects or other types.
 * Directives like @key, @is, @require must have required arguments with correct types.
 *
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Type-Definition-Invalid
 */
public final class TypeDefinitionInvalidRule implements ValidationRule {

    private static final String CODE = "TYPE_DEFINITION_INVALID";

    // Built-in scalars that must remain scalars
    private static final Set<String> BUILT_IN_SCALARS = Set.of(
        "FieldSelectionMap",
        "FieldSelectionSet"
    );

    // Required directive arguments: directive name -> (arg name -> arg type)
    // Per Composite Schemas spec:
    // - FieldSelectionSet: @key and @provides
    // - FieldSelectionMap: @is and @require
    private static final Map<String, Map<String, String>> REQUIRED_DIRECTIVE_ARGS = Map.of(
        KEY, Map.of("fields", "FieldSelectionSet!"),
        IS, Map.of("field", "FieldSelectionMap!"),
        REQUIRE, Map.of("field", "FieldSelectionMap!"),
        PROVIDES, Map.of("fields", "FieldSelectionSet!"),
        OVERRIDE, Map.of("from", "String!")
    );

    @Override
    public ValidationPhase phase() {
        return ValidationPhase.SOURCE_SCHEMA;
    }

    @Override
    public String name() {
        return "TypeDefinitionInvalidRule";
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

        // Validate built-in scalars are actually scalars
        for (String scalarName : BUILT_IN_SCALARS) {
            GraphQLType type = schema.getType(scalarName);
            if (type != null && !(type instanceof GraphQLScalarType)) {
                String actualKind = getTypeKind(type);
                String message = String.format(
                    "Built-in type '%s' must be a scalar, but is defined as %s in schema '%s'.",
                    scalarName, actualKind, schemaName
                );
                builder.addError(CODE, message, scalarName, schemaName);
            }
        }

        // Validate directives have required arguments
        for (Map.Entry<String, Map<String, String>> entry : REQUIRED_DIRECTIVE_ARGS.entrySet()) {
            String directiveName = entry.getKey();
            Map<String, String> requiredArgs = entry.getValue();

            GraphQLDirective directive = schema.getDirective(directiveName);
            if (directive != null) {
                validateDirectiveArguments(directive, requiredArgs, schemaName, builder);
            }
        }
    }

    private void validateDirectiveArguments(GraphQLDirective directive, Map<String, String> requiredArgs,
                                            String schemaName, ValidationResult.Builder builder) {
        String directiveName = directive.getName();

        for (Map.Entry<String, String> argEntry : requiredArgs.entrySet()) {
            String argName = argEntry.getKey();
            String expectedType = argEntry.getValue();

            GraphQLArgument arg = directive.getArgument(argName);
            if (arg == null) {
                String message = String.format(
                    "Directive @%s is missing required argument '%s' in schema '%s'.",
                    directiveName, argName, schemaName
                );
                builder.addError(CODE, message, "@" + directiveName, schemaName);
                continue;
            }

            // Check argument type matches
            String actualType = formatType(arg.getType());
            if (!isTypeCompatible(actualType, expectedType)) {
                String message = String.format(
                    "Directive @%s argument '%s' has type '%s', expected '%s' in schema '%s'.",
                    directiveName, argName, actualType, expectedType, schemaName
                );
                builder.addError(CODE, message, "@" + directiveName, schemaName);
            }
        }
    }

    private String getTypeKind(GraphQLType type) {
        if (type instanceof GraphQLScalarType) return "scalar";
        if (type instanceof GraphQLObjectType) return "object type";
        if (type instanceof GraphQLInterfaceType) return "interface";
        if (type instanceof GraphQLUnionType) return "union";
        if (type instanceof GraphQLEnumType) return "enum";
        if (type instanceof GraphQLInputObjectType) return "input object";
        return "unknown";
    }

    private String formatType(GraphQLInputType type) {
        if (type instanceof GraphQLNonNull nonNull) {
            return formatType((GraphQLInputType) nonNull.getWrappedType()) + "!";
        }
        if (type instanceof GraphQLList list) {
            return "[" + formatType((GraphQLInputType) list.getWrappedType()) + "]";
        }
        if (type instanceof GraphQLNamedType named) {
            return named.getName();
        }
        return type.toString();
    }

    /**
     * Checks if actual type is compatible with expected type.
     * For now, we do exact match but could be relaxed for covariance.
     */
    private boolean isTypeCompatible(String actual, String expected) {
        return actual.equals(expected);
    }
}
