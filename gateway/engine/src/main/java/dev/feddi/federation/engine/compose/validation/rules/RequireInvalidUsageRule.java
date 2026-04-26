package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;

import java.util.List;

import static dev.feddi.federation.engine.compose.FederationDirectives.LOOKUP;
import static dev.feddi.federation.engine.compose.FederationDirectives.REQUIRE;

/**
 * Validates that @require directive is NOT used on arguments of @lookup fields.
 *
 * The @require directive is used on field arguments to declare dependencies
 * on data from other schemas. It cannot be used on @lookup field arguments because:
 * - @lookup fields are entry points for cross-subgraph resolution
 * - @lookup fields use @is to map arguments to source fields
 * - @require is for regular fields that need pre-fetched data to resolve
 *
 * Error Code: REQUIRE_INVALID_USAGE
 */
public final class RequireInvalidUsageRule implements ValidationRule {

    private static final String CODE = "REQUIRE_INVALID_USAGE";

    @Override
    public ValidationPhase phase() {
        return ValidationPhase.SOURCE_SCHEMA;
    }

    @Override
    public String name() {
        return "RequireInvalidUsageRule";
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

        for (GraphQLNamedType type : schema.getAllTypesAsList()) {
            if (type instanceof GraphQLObjectType objectType) {
                validateFieldsContainer(objectType.getName(), objectType.getFieldDefinitions(),
                    subgraph.name(), builder);
            } else if (type instanceof GraphQLInterfaceType interfaceType) {
                validateFieldsContainer(interfaceType.getName(), interfaceType.getFieldDefinitions(),
                    subgraph.name(), builder);
            }
        }
    }

    private void validateFieldsContainer(String typeName, List<GraphQLFieldDefinition> fields,
                                         String schemaName, ValidationResult.Builder builder) {
        for (GraphQLFieldDefinition field : fields) {
            boolean hasLookup = field.hasAppliedDirective(LOOKUP);

            for (GraphQLArgument arg : field.getArguments()) {
                if (arg.hasAppliedDirective(REQUIRE) && hasLookup) {
                    String coordinate = String.format("%s.%s(%s:)", typeName, field.getName(), arg.getName());
                    String message = String.format(
                        "The @require directive on argument '%s' in schema '%s' is invalid " +
                        "because @require cannot be used on @lookup field arguments. " +
                        "@lookup fields use @is to map arguments to key fields.",
                        coordinate, schemaName.toUpperCase()
                    );
                    builder.addError(CODE, message, coordinate, schemaName, REQUIRE);
                }
            }
        }
    }
}
