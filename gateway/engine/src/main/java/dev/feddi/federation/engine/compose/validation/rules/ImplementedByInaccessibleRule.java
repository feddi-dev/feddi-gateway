package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.PostMergeValidationRule;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLImplementingType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedOutputType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;

import java.util.List;
import java.util.Set;

import static dev.feddi.federation.engine.compose.FederationDirectives.INACCESSIBLE;

/**
 * Validates that types implementing an interface have accessible fields for all interface fields.
 * If an interface field is accessible but the implementing type's field is @inaccessible, this is an error.
 *
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Implemented-By-Inaccessible
 */
public final class ImplementedByInaccessibleRule implements PostMergeValidationRule {

    private static final String CODE = "IMPLEMENTED_BY_INACCESSIBLE";

    private static final Set<String> BUILT_IN_TYPES = Set.of(
        "String", "Int", "Float", "Boolean", "ID",
        "__Schema", "__Type", "__Field", "__InputValue", "__EnumValue",
        "__TypeKind", "__Directive", "__DirectiveLocation"
    );

    @Override
    public String name() {
        return "ImplementedByInaccessibleRule";
    }

    @Override
    public ValidationResult validate(GraphQLSchema mergedSchema, List<Subgraph> subgraphs) {
        ValidationResult.Builder builder = ValidationResult.builder();

        for (GraphQLType type : mergedSchema.getAllTypesAsList()) {
            if (type instanceof GraphQLObjectType objType) {
                validateType(objType, builder);
            } else if (type instanceof GraphQLInterfaceType ifaceType) {
                validateType(ifaceType, builder);
            }
        }

        return builder.build();
    }

    private void validateType(GraphQLImplementingType type, ValidationResult.Builder builder) {
        String typeName = ((GraphQLNamedType) type).getName();
        if (BUILT_IN_TYPES.contains(typeName)) {
            return;
        }

        for (GraphQLNamedOutputType implementedInterface : type.getInterfaces()) {
            if (!(implementedInterface instanceof GraphQLInterfaceType iface)) {
                continue;
            }

            for (GraphQLFieldDefinition interfaceField : iface.getFieldDefinitions()) {
                // Skip if interface field is @inaccessible
                if (isInaccessible(interfaceField)) {
                    continue;
                }

                // Check if implementing type has this field and it's accessible
                GraphQLFieldDefinition typeField = type.getFieldDefinition(interfaceField.getName());
                if (typeField == null) {
                    // Field missing - this is a different error (schema validation)
                    continue;
                }

                if (isInaccessible(typeField)) {
                    String message = String.format(
                        "Field '%s.%s' is marked @inaccessible but implements interface field '%s.%s' which is accessible.",
                        typeName, typeField.getName(), iface.getName(), interfaceField.getName()
                    );
                    builder.addError(CODE, message, typeName + "." + typeField.getName(), null);
                }
            }
        }
    }

    private boolean isInaccessible(GraphQLFieldDefinition field) {
        return field.hasAppliedDirective(INACCESSIBLE);
    }
}
