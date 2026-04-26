package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLDirectiveContainer;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static dev.feddi.federation.engine.compose.FederationDirectives.INACCESSIBLE;

/**
 * Validates that accessible fields and arguments only reference accessible types.
 *
 * In source schemas, public fields and arguments must only reference types that are
 * accessible. This ensures that public types do not reference inaccessible structures
 * which are intended for internal use.
 *
 * Note: This is implemented as a PRE_MERGE rule because @inaccessible types are removed
 * during the merge process. Validating at the source schema level catches the same errors
 * while allowing the merge to proceed correctly.
 *
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Reference-To-Inaccessible-Type
 */
public final class ReferenceToInaccessibleTypeRule implements ValidationRule {

    private static final String CODE = "REFERENCE_TO_INACCESSIBLE_TYPE";

    private static final Set<String> BUILT_IN_TYPES = Set.of(
        "String", "Int", "Float", "Boolean", "ID",
        "__Schema", "__Type", "__Field", "__InputValue", "__EnumValue",
        "__TypeKind", "__Directive", "__DirectiveLocation"
    );

    @Override
    public ValidationPhase phase() {
        return ValidationPhase.PRE_MERGE;
    }

    @Override
    public String name() {
        return "ReferenceToInaccessibleTypeRule";
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

        // Collect all inaccessible type names in this schema
        Set<String> inaccessibleTypes = collectInaccessibleTypes(schema);

        if (inaccessibleTypes.isEmpty()) {
            return; // No inaccessible types, nothing to validate
        }

        // Check input types
        for (GraphQLType type : schema.getAllTypesAsList()) {
            if (type instanceof GraphQLInputObjectType inputType) {
                checkInputType(inputType, inaccessibleTypes, schemaName, builder);
            } else if (type instanceof GraphQLObjectType objectType) {
                checkOutputFields(objectType.getName(), objectType.getFieldDefinitions(),
                    objectType, inaccessibleTypes, schemaName, builder);
            } else if (type instanceof GraphQLInterfaceType interfaceType) {
                checkOutputFields(interfaceType.getName(), interfaceType.getFieldDefinitions(),
                    interfaceType, inaccessibleTypes, schemaName, builder);
            }
        }
    }

    private void checkInputType(GraphQLInputObjectType inputType, Set<String> inaccessibleTypes,
                                String schemaName, ValidationResult.Builder builder) {
        if (BUILT_IN_TYPES.contains(inputType.getName())) {
            return;
        }
        // Skip inaccessible types
        if (inputType.hasAppliedDirective(INACCESSIBLE)) {
            return;
        }

        for (GraphQLInputObjectField field : inputType.getFieldDefinitions()) {
            // Skip inaccessible fields
            if (field.hasAppliedDirective(INACCESSIBLE)) {
                continue;
            }

            String referencedType = GraphQLTypeUtil.unwrapAll(field.getType()).getName();
            if (inaccessibleTypes.contains(referencedType)) {
                String message = String.format(
                    "Input field '%s.%s' in schema '%s' references inaccessible type '%s'.",
                    inputType.getName(), field.getName(), schemaName, referencedType
                );
                builder.addError(CODE, message, inputType.getName() + "." + field.getName(), schemaName);
            }
        }
    }

    private void checkOutputFields(String typeName, List<GraphQLFieldDefinition> fields,
                                   GraphQLDirectiveContainer typeContainer, Set<String> inaccessibleTypes,
                                   String schemaName, ValidationResult.Builder builder) {
        if (BUILT_IN_TYPES.contains(typeName)) {
            return;
        }
        // Skip inaccessible types
        if (typeContainer.hasAppliedDirective(INACCESSIBLE)) {
            return;
        }

        for (GraphQLFieldDefinition field : fields) {
            // Skip inaccessible fields
            if (field.hasAppliedDirective(INACCESSIBLE)) {
                continue;
            }

            // Check the field's return type
            String returnType = GraphQLTypeUtil.unwrapAll(field.getType()).getName();
            if (inaccessibleTypes.contains(returnType)) {
                String message = String.format(
                    "Output field '%s.%s' in schema '%s' references inaccessible type '%s'.",
                    typeName, field.getName(), schemaName, returnType
                );
                builder.addError(CODE, message, typeName + "." + field.getName(), schemaName);
            }

            // Check arguments
            for (GraphQLArgument arg : field.getArguments()) {
                // Skip inaccessible arguments
                if (arg.hasAppliedDirective(INACCESSIBLE)) {
                    continue;
                }

                String argType = GraphQLTypeUtil.unwrapAll(arg.getType()).getName();
                if (inaccessibleTypes.contains(argType)) {
                    String message = String.format(
                        "Argument '%s' on field '%s.%s' in schema '%s' references inaccessible type '%s'.",
                        arg.getName(), typeName, field.getName(), schemaName, argType
                    );
                    builder.addError(CODE, message, typeName + "." + field.getName() + "." + arg.getName(), schemaName);
                }
            }
        }
    }

    private Set<String> collectInaccessibleTypes(GraphQLSchema schema) {
        Set<String> inaccessibleTypes = new HashSet<>();
        for (GraphQLNamedType type : schema.getAllTypesAsList()) {
            if (type instanceof GraphQLDirectiveContainer container) {
                if (container.hasAppliedDirective(INACCESSIBLE)) {
                    inaccessibleTypes.add(type.getName());
                }
            }
        }
        return inaccessibleTypes;
    }
}
