package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static dev.feddi.federation.engine.compose.FederationDirectives.LOOKUP;
import static dev.feddi.federation.engine.compose.FederationDirectives.REQUIRE;

/**
 * Rejects {@code @require} on fields of the root operation types.
 *
 * <p>Not a spec rule: the spec only forbids {@code @require} on lookup fields (REQUIRE_INVALID_USAGE, reported
 * there). feddi resolves requirements when it enters an object through a lookup, which a root field never is,
 * so a requirement on a root field would be dropped instead of resolved. Requirements on other types need no
 * rule of their own: satisfiability rejects those that cannot be resolved.
 */
public final class RequireOnRootFieldRule implements ValidationRule {

    private static final String CODE = "REQUIRE_ON_ROOT_FIELD";

    @Override
    public ValidationPhase phase() {
        return ValidationPhase.SOURCE_SCHEMA;
    }

    @Override
    public String name() {
        return "RequireOnRootFieldRule";
    }

    @Override
    public ValidationResult validate(List<Subgraph> subgraphs) {
        ValidationResult.Builder builder = ValidationResult.builder();
        for (Subgraph subgraph : subgraphs) {
            GraphQLSchema schema = subgraph.schema();
            Stream.of(schema.getQueryType(), schema.getMutationType(), schema.getSubscriptionType())
                .filter(Objects::nonNull)
                .forEach(rootType -> validateRootType(rootType, subgraph.name(), builder));
        }
        return builder.build();
    }

    private static void validateRootType(GraphQLObjectType rootType, String schemaName,
                                         ValidationResult.Builder builder) {
        for (GraphQLFieldDefinition field : rootType.getFieldDefinitions()) {
            if (field.hasAppliedDirective(LOOKUP)) {
                continue;
            }
            for (GraphQLArgument argument : field.getArguments()) {
                if (argument.hasAppliedDirective(REQUIRE)) {
                    String coordinate = String.format("%s.%s(%s:)", rootType.getName(), field.getName(),
                        argument.getName());
                    builder.addError(CODE, String.format(
                        "The @require directive on argument '%s' in schema '%s' is not supported: feddi resolves "
                            + "requirements of fields of objects it enters through a lookup, not of root fields.",
                        coordinate, schemaName), coordinate, schemaName, REQUIRE);
                }
            }
        }
    }
}
