package dev.feddi.federation.engine.compose.validation.rules;

import static dev.feddi.federation.engine.compose.FederationDirectives.*;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import graphql.Directives;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLDirective;
import graphql.schema.GraphQLDirectiveContainer;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;

import java.util.List;
import java.util.Set;

/**
 * Validates that built-in scalars, introspection types, and built-in directive arguments
 * are not marked as @inaccessible.
 *
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Disallowed-Inaccessible-Elements
 */
public final class DisallowedInaccessibleRule implements ValidationRule {

    private static final String CODE = "DISALLOWED_INACCESSIBLE";

    private static final Set<String> BUILT_IN_SCALARS = Set.of(
        "String", "Int", "Float", "Boolean", "ID"
    );

    private static final Set<String> INTROSPECTION_TYPES = Set.of(
        "__Schema", "__Type", "__Field", "__InputValue", "__EnumValue",
        "__TypeKind", "__Directive", "__DirectiveLocation"
    );

    private static final Set<String> BUILT_IN_DIRECTIVES = Set.of(
        Directives.SkipDirective.getName(),
        Directives.IncludeDirective.getName(),
        Directives.DeprecatedDirective.getName(),
        Directives.SpecifiedByDirective.getName()
    );

    @Override
    public ValidationPhase phase() {
        return ValidationPhase.SOURCE_SCHEMA;
    }

    @Override
    public String name() {
        return "DisallowedInaccessibleRule";
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

        // Check built-in scalars
        for (String scalarName : BUILT_IN_SCALARS) {
            GraphQLType type = schema.getType(scalarName);
            if (type != null && hasInaccessible(type)) {
                String message = String.format(
                    "Built-in scalar '%s' in schema '%s' must not be marked as @inaccessible.",
                    scalarName, subgraph.name()
                );
                builder.addError(CODE, message, scalarName, subgraph.name());
            }
        }

        // Check introspection types
        for (String typeName : INTROSPECTION_TYPES) {
            GraphQLType type = schema.getType(typeName);
            if (type != null && hasInaccessible(type)) {
                String message = String.format(
                    "Introspection type '%s' in schema '%s' must not be marked as @inaccessible.",
                    typeName, subgraph.name()
                );
                builder.addError(CODE, message, typeName, subgraph.name());
            }

            // Also check fields and arguments of introspection types
            if (type instanceof GraphQLObjectType objType) {
                for (GraphQLFieldDefinition field : objType.getFieldDefinitions()) {
                    if (hasInaccessible(field)) {
                        String message = String.format(
                            "Field '%s' on introspection type '%s' in schema '%s' must not be marked as @inaccessible.",
                            field.getName(), typeName, subgraph.name()
                        );
                        builder.addError(CODE, message, typeName + "." + field.getName(), subgraph.name());
                    }
                    for (GraphQLArgument arg : field.getArguments()) {
                        if (hasInaccessible(arg)) {
                            String message = String.format(
                                "Argument '%s' on field '%s.%s' in schema '%s' must not be marked as @inaccessible.",
                                arg.getName(), typeName, field.getName(), subgraph.name()
                            );
                            builder.addError(CODE, message, typeName + "." + field.getName() + "." + arg.getName(), subgraph.name());
                        }
                    }
                }
            }
        }

        // Check built-in directive arguments
        for (String directiveName : BUILT_IN_DIRECTIVES) {
            GraphQLDirective directive = schema.getDirective(directiveName);
            if (directive != null) {
                for (GraphQLArgument arg : directive.getArguments()) {
                    if (hasInaccessible(arg)) {
                        String message = String.format(
                            "Argument '%s' on built-in directive '@%s' in schema '%s' must not be marked as @inaccessible.",
                            arg.getName(), directiveName, subgraph.name()
                        );
                        builder.addError(CODE, message, "@" + directiveName + "." + arg.getName(), subgraph.name());
                    }
                }
            }
        }
    }

    private boolean hasInaccessible(GraphQLType type) {
        if (type instanceof GraphQLDirectiveContainer container) {
            return container.hasAppliedDirective(INACCESSIBLE);
        }
        return false;
    }

    private boolean hasInaccessible(GraphQLFieldDefinition field) {
        return field.hasAppliedDirective(INACCESSIBLE);
    }

    private boolean hasInaccessible(GraphQLArgument arg) {
        return arg.hasAppliedDirective(INACCESSIBLE);
    }
}
