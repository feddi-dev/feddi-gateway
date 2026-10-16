package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLImplementingType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedOutputType;
import graphql.schema.GraphQLNamedType;

import java.util.List;

import static dev.feddi.federation.engine.compose.FederationDirectives.REQUIRE;

/**
 * Validates that an argument of an interface field and the same argument of each implementing field are either
 * both annotated with {@code @require} or neither is: composition removes such arguments, and removing one on only
 * one side would break the interface contract.
 *
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Require-Inconsistent-on-Implementation
 */
public final class RequireInconsistentOnImplementationRule implements ValidationRule {

    private static final String CODE = "REQUIRE_INCONSISTENT_ON_IMPLEMENTATION";

    @Override
    public ValidationPhase phase() {
        return ValidationPhase.SOURCE_SCHEMA;
    }

    @Override
    public String name() {
        return "RequireInconsistentOnImplementationRule";
    }

    @Override
    public ValidationResult validate(List<Subgraph> subgraphs) {
        ValidationResult.Builder builder = ValidationResult.builder();
        for (Subgraph subgraph : subgraphs) {
            for (GraphQLNamedType type : subgraph.schema().getAllTypesAsList()) {
                if (type instanceof GraphQLImplementingType implementingType) {
                    for (GraphQLNamedOutputType implemented : implementingType.getInterfaces()) {
                        if (implemented instanceof GraphQLInterfaceType interfaceType) {
                            validate(implementingType, interfaceType, subgraph.name(), builder);
                        }
                    }
                }
            }
        }
        return builder.build();
    }

    private static void validate(GraphQLImplementingType implementingType, GraphQLInterfaceType interfaceType,
                                 String schemaName, ValidationResult.Builder builder) {
        for (GraphQLFieldDefinition interfaceField : interfaceType.getFieldDefinitions()) {
            GraphQLFieldDefinition implementingField = implementingType.getFieldDefinition(interfaceField.getName());
            if (implementingField == null) {
                continue; // not valid GraphQL, reported when the schema is built
            }
            for (GraphQLArgument interfaceArgument : interfaceField.getArguments()) {
                GraphQLArgument implementingArgument = implementingField.getArgument(interfaceArgument.getName());
                if (implementingArgument == null) {
                    continue;
                }
                boolean interfaceRequires = interfaceArgument.hasAppliedDirective(REQUIRE);
                if (interfaceRequires != implementingArgument.hasAppliedDirective(REQUIRE)) {
                    String coordinate = String.format("%s.%s(%s:)", implementingType.getName(),
                        implementingField.getName(), implementingArgument.getName());
                    builder.addError(CODE, String.format(
                        "Argument '%s' in schema '%s' %s @require, but the argument it implements on interface "
                            + "'%s' %s; both must be annotated with @require or neither.",
                        coordinate, schemaName, interfaceRequires ? "is not annotated with" : "is annotated with",
                        interfaceType.getName(), interfaceRequires ? "is" : "is not"), coordinate, schemaName, REQUIRE);
                }
            }
        }
    }
}
