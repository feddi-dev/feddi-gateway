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
import java.util.Set;

import static dev.feddi.federation.engine.compose.FederationDirectives.EXTERNAL;

/**
 * Validates that @external fields do not have arguments.
 *
 * <h2>Rationale</h2>
 * The GraphQL Composite Schemas Specification has conflicting rules regarding
 * @external fields with arguments:
 * <ul>
 *   <li>EXTERNAL_UNUSED requires @external fields to be referenced by @provides</li>
 *   <li>PROVIDES_FIELDS_HAS_ARGUMENTS forbids @provides from referencing fields with arguments</li>
 *   <li>EXTERNAL_ARGUMENT_* rules imply @external fields can have arguments</li>
 * </ul>
 *
 * These rules are mutually exclusive in practice. To have a sound and consistent
 * validation system, we enforce that @external fields cannot have arguments.
 * This simplifies the model and avoids unreachable validation states.
 *
 * <h2>Spec Note</h2>
 * This rule is stricter than the spec. The spec defines EXTERNAL_ARGUMENT_MISSING,
 * EXTERNAL_ARGUMENT_TYPE_MISMATCH, and EXTERNAL_ARGUMENT_DEFAULT_MISMATCH rules
 * which imply @external fields can have arguments. However, due to the interaction
 * with EXTERNAL_UNUSED and PROVIDES_FIELDS_HAS_ARGUMENTS, those rules are effectively
 * unreachable. This implementation makes that constraint explicit by forbidding
 * arguments entirely.
 */
public final class ExternalFieldHasArgumentsRule implements ValidationRule {

    private static final String CODE = "EXTERNAL_FIELD_HAS_ARGUMENTS";

    private static final Set<String> BUILT_IN_TYPES = Set.of(
        "String", "Int", "Float", "Boolean", "ID",
        "__Schema", "__Type", "__Field", "__InputValue", "__EnumValue",
        "__TypeKind", "__Directive", "__DirectiveLocation"
    );

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
            if (BUILT_IN_TYPES.contains(type.getName())) {
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
