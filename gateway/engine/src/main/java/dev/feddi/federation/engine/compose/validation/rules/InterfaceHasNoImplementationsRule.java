package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.PostMergeValidationRule;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedOutputType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static dev.feddi.federation.engine.compose.FederationDirectives.INACCESSIBLE;

/**
 * Validates that interface types used as return types have at least one implementing type.
 *
 * An interface with no implementations cannot be resolved at runtime, as GraphQL requires
 * abstract types to resolve to concrete types when returning data.
 *
 * This rule only reports an error if the interface is actually used as a return type
 * somewhere in the schema. Unused interfaces with no implementations are allowed.
 */
public final class InterfaceHasNoImplementationsRule implements PostMergeValidationRule {

    private static final String CODE = "INTERFACE_HAS_NO_IMPLEMENTATIONS";

    private static final Set<String> BUILT_IN_TYPES = Set.of(
        "String", "Int", "Float", "Boolean", "ID",
        "__Schema", "__Type", "__Field", "__InputValue", "__EnumValue",
        "__TypeKind", "__Directive", "__DirectiveLocation"
    );

    @Override
    public String name() {
        return "InterfaceHasNoImplementationsRule";
    }

    @Override
    public ValidationResult validate(GraphQLSchema mergedSchema, List<Subgraph> subgraphs) {
        ValidationResult.Builder builder = ValidationResult.builder();

        // Find all interfaces that have no implementations
        Set<String> interfacesWithNoImpl = new HashSet<>();
        for (GraphQLNamedType type : mergedSchema.getAllTypesAsList()) {
            if (type instanceof GraphQLInterfaceType interfaceType) {
                if (BUILT_IN_TYPES.contains(interfaceType.getName())) {
                    continue;
                }
                // Skip @inaccessible interfaces
                if (interfaceType.hasAppliedDirective(INACCESSIBLE)) {
                    continue;
                }
                List<GraphQLObjectType> implementations = mergedSchema.getImplementations(interfaceType);
                if (implementations.isEmpty()) {
                    interfacesWithNoImpl.add(interfaceType.getName());
                }
            }
        }

        if (interfacesWithNoImpl.isEmpty()) {
            return builder.build();
        }

        // Find which interfaces are used as return types
        Set<String> usedAsReturnType = new HashSet<>();
        for (GraphQLNamedType type : mergedSchema.getAllTypesAsList()) {
            if (type instanceof GraphQLObjectType objectType) {
                checkFieldReturnTypes(objectType.getFieldDefinitions(), interfacesWithNoImpl, usedAsReturnType);
            } else if (type instanceof GraphQLInterfaceType interfaceType) {
                checkFieldReturnTypes(interfaceType.getFieldDefinitions(), interfacesWithNoImpl, usedAsReturnType);
            }
        }

        // Report errors for interfaces with no implementations that are used as return types
        for (String interfaceName : usedAsReturnType) {
            String message = String.format(
                "Interface type '%s' has no implementing types but is used as a return type. " +
                "At least one type must implement this interface for it to be resolvable at runtime.",
                interfaceName
            );
            builder.addError(CODE, message, interfaceName, null, null);
        }

        return builder.build();
    }

    private void checkFieldReturnTypes(List<GraphQLFieldDefinition> fields,
                                       Set<String> interfacesWithNoImpl,
                                       Set<String> usedAsReturnType) {
        for (GraphQLFieldDefinition field : fields) {
            // Skip @inaccessible fields
            if (field.hasAppliedDirective(INACCESSIBLE)) {
                continue;
            }
            GraphQLOutputType returnType = field.getType();
            GraphQLType unwrapped = GraphQLTypeUtil.unwrapAll(returnType);
            if (unwrapped instanceof GraphQLNamedOutputType namedType) {
                String typeName = namedType.getName();
                if (interfacesWithNoImpl.contains(typeName)) {
                    usedAsReturnType.add(typeName);
                }
            }
        }
    }
}
