package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedOutputType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnionType;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import static dev.feddi.federation.engine.compose.FederationDirectives.INACCESSIBLE;

/**
 * Validates that all Interface, Object, and Union types in a subgraph are reachable
 * from the root types (Query, Mutation, Subscription).
 *
 * <p>Unreachable types indicate dead code in the schema that should be removed.
 * This rule helps maintain clean, focused subgraph schemas.
 *
 * <p><b>Note on @key directive:</b> The @key directive does NOT make a type automatically
 * reachable. In this federation implementation, @key is only used as a convenient way to
 * declare key fields as shareable. Types must have explicit @lookup fields to be accessible
 * via entity resolution. A type with @key but no @lookup field pointing to it (directly or
 * indirectly from a root type) is considered unreachable.
 *
 * <p><b>Note on @inaccessible directive:</b> Types marked with @inaccessible are exempt
 * from this check. These types are explicitly marked as internal and may be used by
 * resolvers or for other internal purposes even if not exposed in the GraphQL schema.
 *
 * <p>The reachability check:
 * <ul>
 *   <li>Starts from Query, Mutation, and Subscription root types</li>
 *   <li>Follows field return types to discover reachable types</li>
 *   <li>For interfaces, includes all implementing types as reachable</li>
 *   <li>For unions, includes all member types as reachable</li>
 *   <li>For input types used as field arguments, traverses nested input types</li>
 * </ul>
 */
public final class UnreachableTypeRule implements ValidationRule {

    private static final String CODE = "UNREACHABLE_TYPE";

    private static final Set<String> BUILT_IN_TYPES = Set.of(
        "String", "Int", "Float", "Boolean", "ID",
        "__Schema", "__Type", "__Field", "__InputValue", "__EnumValue",
        "__TypeKind", "__Directive", "__DirectiveLocation"
    );

    @Override
    public String name() {
        return "UnreachableTypeRule";
    }

    @Override
    public ValidationPhase phase() {
        return ValidationPhase.SOURCE_SCHEMA;
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
        Set<String> reachableTypes = findReachableTypes(schema);

        // Check all types in the schema
        for (GraphQLNamedType type : schema.getAllTypesAsList()) {
            String typeName = type.getName();

            // Skip built-in types
            if (BUILT_IN_TYPES.contains(typeName)) {
                continue;
            }

            // Skip scalar and enum types - they're typically shared/common types
            if (!(type instanceof GraphQLObjectType)
                    && !(type instanceof GraphQLInterfaceType)
                    && !(type instanceof GraphQLUnionType)) {
                continue;
            }

            // Skip the root types themselves
            if (isRootType(schema, typeName)) {
                continue;
            }

            // Skip @inaccessible types - they're explicitly marked as internal
            // and may be used by resolvers even if not exposed in the schema
            if (hasInaccessibleDirective(type)) {
                continue;
            }

            // Check if the type is reachable
            if (!reachableTypes.contains(typeName)) {
                String message = String.format(
                    "Type '%s' in subgraph '%s' is not reachable from any root type (Query/Mutation/Subscription). " +
                    "Remove unused types to keep the schema clean.",
                    typeName, subgraph.name()
                );
                builder.addError(CODE, message, typeName, subgraph.name(), null);
            }
        }
    }

    private boolean isRootType(GraphQLSchema schema, String typeName) {
        GraphQLObjectType queryType = schema.getQueryType();
        GraphQLObjectType mutationType = schema.getMutationType();
        GraphQLObjectType subscriptionType = schema.getSubscriptionType();

        return (queryType != null && queryType.getName().equals(typeName))
            || (mutationType != null && mutationType.getName().equals(typeName))
            || (subscriptionType != null && subscriptionType.getName().equals(typeName));
    }

    private boolean hasInaccessibleDirective(GraphQLNamedType type) {
        if (type instanceof GraphQLObjectType objectType) {
            return objectType.hasAppliedDirective(INACCESSIBLE);
        }
        if (type instanceof GraphQLInterfaceType interfaceType) {
            return interfaceType.hasAppliedDirective(INACCESSIBLE);
        }
        if (type instanceof GraphQLUnionType unionType) {
            return unionType.hasAppliedDirective(INACCESSIBLE);
        }
        return false;
    }

    /**
     * Finds all types reachable from the root types via field traversal.
     */
    private Set<String> findReachableTypes(GraphQLSchema schema) {
        Set<String> visited = new HashSet<>();
        Queue<GraphQLNamedType> toVisit = new LinkedList<>();

        // Start with root types
        if (schema.getQueryType() != null) {
            toVisit.add(schema.getQueryType());
        }
        if (schema.getMutationType() != null) {
            toVisit.add(schema.getMutationType());
        }
        if (schema.getSubscriptionType() != null) {
            toVisit.add(schema.getSubscriptionType());
        }

        while (!toVisit.isEmpty()) {
            GraphQLNamedType type = toVisit.poll();
            String typeName = type.getName();

            if (visited.contains(typeName) || BUILT_IN_TYPES.contains(typeName)) {
                continue;
            }
            visited.add(typeName);

            // Process based on type kind
            if (type instanceof GraphQLObjectType objectType) {
                processObjectType(objectType, schema, visited, toVisit);
            } else if (type instanceof GraphQLInterfaceType interfaceType) {
                processInterfaceType(interfaceType, schema, visited, toVisit);
            } else if (type instanceof GraphQLUnionType unionType) {
                processUnionType(unionType, visited, toVisit);
            } else if (type instanceof GraphQLInputObjectType inputType) {
                processInputType(inputType, visited, toVisit);
            }
        }

        return visited;
    }

    private void processObjectType(GraphQLObjectType objectType, GraphQLSchema schema,
                                   Set<String> visited, Queue<GraphQLNamedType> toVisit) {
        // Process all fields
        for (GraphQLFieldDefinition field : objectType.getFieldDefinitions()) {
            processFieldReturnType(field, visited, toVisit);
            processFieldArguments(field, visited, toVisit);
        }

        // Process implemented interfaces
        for (GraphQLNamedOutputType iface : objectType.getInterfaces()) {
            if (!visited.contains(iface.getName())) {
                toVisit.add(iface);
            }
        }
    }

    private void processInterfaceType(GraphQLInterfaceType interfaceType, GraphQLSchema schema,
                                      Set<String> visited, Queue<GraphQLNamedType> toVisit) {
        // Process all fields
        for (GraphQLFieldDefinition field : interfaceType.getFieldDefinitions()) {
            processFieldReturnType(field, visited, toVisit);
            processFieldArguments(field, visited, toVisit);
        }

        // Process all implementing types - they're reachable via the interface
        for (GraphQLObjectType impl : schema.getImplementations(interfaceType)) {
            if (!visited.contains(impl.getName())) {
                toVisit.add(impl);
            }
        }
    }

    private void processUnionType(GraphQLUnionType unionType,
                                  Set<String> visited, Queue<GraphQLNamedType> toVisit) {
        // Process all member types
        for (GraphQLNamedOutputType member : unionType.getTypes()) {
            if (!visited.contains(member.getName())) {
                toVisit.add(member);
            }
        }
    }

    private void processInputType(GraphQLInputObjectType inputType,
                                  Set<String> visited, Queue<GraphQLNamedType> toVisit) {
        // Process all input fields
        for (GraphQLInputObjectField field : inputType.getFieldDefinitions()) {
            GraphQLType unwrapped = GraphQLTypeUtil.unwrapAll(field.getType());
            if (unwrapped instanceof GraphQLNamedType namedType) {
                if (!visited.contains(namedType.getName())) {
                    toVisit.add(namedType);
                }
            }
        }
    }

    private void processFieldReturnType(GraphQLFieldDefinition field,
                                        Set<String> visited, Queue<GraphQLNamedType> toVisit) {
        GraphQLType unwrapped = GraphQLTypeUtil.unwrapAll(field.getType());
        if (unwrapped instanceof GraphQLNamedType namedType) {
            if (!visited.contains(namedType.getName())) {
                toVisit.add(namedType);
            }
        }
    }

    private void processFieldArguments(GraphQLFieldDefinition field,
                                       Set<String> visited, Queue<GraphQLNamedType> toVisit) {
        for (var argument : field.getArguments()) {
            GraphQLType unwrapped = GraphQLTypeUtil.unwrapAll(argument.getType());
            if (unwrapped instanceof GraphQLNamedType namedType) {
                if (!visited.contains(namedType.getName())) {
                    toVisit.add(namedType);
                }
            }
        }
    }
}
