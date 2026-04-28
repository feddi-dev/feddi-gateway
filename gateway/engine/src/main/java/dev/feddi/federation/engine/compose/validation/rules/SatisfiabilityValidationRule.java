package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.Constants;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.graph.Graph;
import dev.feddi.federation.engine.graph.Node;
import dev.feddi.federation.engine.planner.OperationPath;
import dev.feddi.federation.engine.planner.PathFinder;
import dev.feddi.federation.engine.compose.validation.PostGraphValidationRule;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedOutputType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnionType;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates that every field in the composed schema is satisfiable using
 * full path-based validation.
 *
 * This validation simulates query planning by tracing all possible query paths
 * from root operations and verifying that every field along every path is
 * resolvable from the specific node position reached via that path.
 *
 * Key insight: A field might be resolvable from one subgraph's node but not
 * from another. The path-based approach ensures fields are reachable from
 * the actual positions a query would reach at runtime.
 *
 * Example problem caught:
 * - Schema A: Query.getFoo returns Foo with {id, name}
 * - Schema B: Foo has {id, extra} but NO @key/@lookup
 * - Foo.extra is unreachable because no lookup path exists from A to B
 *
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Validate-Satisfiability
 */
public final class SatisfiabilityValidationRule implements PostGraphValidationRule {

    private static final String CODE = "SATISFIABILITY_ERROR";

    @Override
    public String name() {
        return "SatisfiabilityValidationRule";
    }

    @Override
    public ValidationResult validate(Graph graph, GraphQLSchema mergedSchema, List<Subgraph> subgraphs) {
        SatisfiabilityContext ctx = new SatisfiabilityContext(graph, mergedSchema);
        ctx.validateFromRoots();
        return ctx.buildResult();
    }

    /**
     * Context for path-based satisfiability validation.
     * Tracks visited (type, node) pairs to handle circular references.
     */
    private static class SatisfiabilityContext {
        private final Graph graph;
        private final PathFinder pathFinder;
        private final GraphQLSchema schema;
        private final Set<TypeNodePair> visited = new HashSet<>();
        private final ValidationResult.Builder resultBuilder = ValidationResult.builder();

        /**
         * Unique key for a (type, node) pair to prevent infinite loops.
         */
        private record TypeNodePair(String typeName, Node node) {}

        SatisfiabilityContext(Graph graph, GraphQLSchema schema) {
            this.graph = graph;
            this.pathFinder = new PathFinder(graph);
            this.schema = schema;
        }

        /**
         * Returns true if this (type, node) pair should be visited.
         * Returns false if already visited (prevents infinite loops).
         */
        boolean shouldVisit(String typeName, Node node) {
            return visited.add(new TypeNodePair(typeName, node));
        }

        void addError(String typeName, String fieldName, Node currentNode, String detail) {
            String message = String.format(
                "Field '%s.%s' cannot be resolved from %s/%s. %s",
                typeName, fieldName, typeName, currentNode.subgraph(), detail
            );
            resultBuilder.addError(CODE, message, typeName + "." + fieldName, null);
        }

        ValidationResult buildResult() {
            return resultBuilder.build();
        }

        /**
         * Entry point: validate from all root operation types.
         */
        void validateFromRoots() {
            // Query
            GraphQLObjectType queryType = schema.getQueryType();
            if (queryType != null) {
                Node rootNode = graph.getRootNode(Constants.QUERY);
                if (rootNode != null) {
                    validateObjectType(queryType, rootNode);
                }
            }

            // Mutation
            GraphQLObjectType mutationType = schema.getMutationType();
            if (mutationType != null) {
                Node rootNode = graph.getRootNode(Constants.MUTATION);
                if (rootNode != null) {
                    validateObjectType(mutationType, rootNode);
                }
            }

            // Subscription
            GraphQLObjectType subscriptionType = schema.getSubscriptionType();
            if (subscriptionType != null) {
                Node rootNode = graph.getRootNode(Constants.SUBSCRIPTION);
                if (rootNode != null) {
                    validateObjectType(subscriptionType, rootNode);
                }
            }
        }

        /**
         * Validates all fields of an object type from a specific node position.
         */
        void validateObjectType(GraphQLObjectType type, Node currentNode) {
            String typeName = type.getName();

            // Skip if already validated from this node
            if (!shouldVisit(typeName, currentNode)) {
                return;
            }

            OperationPath startPath = OperationPath.startAt(currentNode);

            for (GraphQLFieldDefinition field : type.getFieldDefinitions()) {
                String fieldName = field.getName();

                // Skip introspection fields
                if (fieldName.startsWith("__")) {
                    continue;
                }

                List<OperationPath> paths = pathFinder.findPaths(startPath, fieldName);

                if (paths.isEmpty()) {
                    addError(typeName, fieldName, currentNode,
                        "No path exists in the planning graph to resolve this field.");
                    continue;
                }

                // Validate nested types from ALL reachable target nodes
                // This ensures fields are resolvable regardless of which path the planner chooses
                GraphQLType returnType = GraphQLTypeUtil.unwrapAll(field.getType());
                Set<Node> validatedTargets = new HashSet<>();

                for (OperationPath path : paths) {
                    Node targetNode = path.tail();
                    // Only validate from each unique target node once
                    if (validatedTargets.add(targetNode)) {
                        validateNestedType(returnType, targetNode);
                    }
                }
            }
        }

        /**
         * Validates all fields of an interface type from a specific node position.
         * Interface fields might be resolved via implementing types.
         */
        void validateInterfaceType(GraphQLInterfaceType interfaceType, Node currentNode) {
            String typeName = interfaceType.getName();

            if (!shouldVisit(typeName, currentNode)) {
                return;
            }

            OperationPath startPath = OperationPath.startAt(currentNode);

            // Validate fields declared on the interface
            for (GraphQLFieldDefinition field : interfaceType.getFieldDefinitions()) {
                String fieldName = field.getName();

                if (fieldName.startsWith("__")) {
                    continue;
                }

                List<OperationPath> paths = pathFinder.findPaths(startPath, fieldName);

                if (paths.isEmpty()) {
                    // Try to resolve via implementing types with type context
                    boolean canResolveViaImpl = tryResolveViaImplementations(
                        interfaceType, currentNode, field);

                    if (!canResolveViaImpl) {
                        addError(typeName, fieldName, currentNode,
                            "No path exists to resolve this field, even via implementing types.");
                    }
                } else {
                    // Validate nested types from target nodes
                    GraphQLType returnType = GraphQLTypeUtil.unwrapAll(field.getType());
                    Set<Node> validatedTargets = new HashSet<>();

                    for (OperationPath path : paths) {
                        Node targetNode = path.tail();
                        if (validatedTargets.add(targetNode)) {
                            validateNestedType(returnType, targetNode);
                        }
                    }
                }
            }

            // Also validate each implementing type from this position
            // (simulates inline fragment narrowing via `... on Article { }`)
            // When at an interface node like Content/content, implementing types
            // are accessed via nodes like Article/content, Video/content
            // Only validate implementing types that exist in the current subgraph
            for (GraphQLObjectType impl : schema.getImplementations(interfaceType)) {
                Node implNode = new Node(impl.getName(), currentNode.subgraph());
                if (graph.containsNode(implNode)) {
                    validateObjectType(impl, implNode);
                }
            }
        }

        /**
         * Try to resolve an interface field via implementing types.
         * Returns true if the field can be resolved from at least one implementation.
         */
        private boolean tryResolveViaImplementations(GraphQLInterfaceType interfaceType,
                                                      Node currentNode,
                                                      GraphQLFieldDefinition field) {
            OperationPath startPath = OperationPath.startAt(currentNode);
            String fieldName = field.getName();
            GraphQLType returnType = GraphQLTypeUtil.unwrapAll(field.getType());

            for (GraphQLObjectType impl : schema.getImplementations(interfaceType)) {
                // Try with type context narrowed to the implementing type
                OperationPath implPath = startPath.withTypeContext(impl.getName());
                List<OperationPath> implPaths = pathFinder.findPaths(implPath, fieldName);

                if (!implPaths.isEmpty()) {
                    // Found a path - validate nested types
                    Set<Node> validatedTargets = new HashSet<>();
                    for (OperationPath path : implPaths) {
                        Node targetNode = path.tail();
                        if (validatedTargets.add(targetNode)) {
                            validateNestedType(returnType, targetNode);
                        }
                    }
                    return true;
                }
            }

            return false;
        }

        /**
         * Validates all member types of a union from a specific node position.
         */
        void validateUnionType(GraphQLUnionType unionType, Node currentNode) {
            String typeName = unionType.getName();

            if (!shouldVisit(typeName, currentNode)) {
                return;
            }

            // Validate each member type (simulates inline fragment narrowing)
            // When at a union node like SearchResult/search, member types
            // are accessed via nodes like Product/search, User/search
            // Only validate member types that exist in the current subgraph
            for (GraphQLNamedOutputType member : unionType.getTypes()) {
                if (member instanceof GraphQLObjectType objectType) {
                    Node memberNode = new Node(objectType.getName(), currentNode.subgraph());
                    // Only validate if this type exists in the current subgraph
                    // (the union may have been merged with additional members from other subgraphs)
                    if (graph.containsNode(memberNode)) {
                        validateObjectType(objectType, memberNode);
                    }
                }
            }
        }

        /**
         * Validates a nested type from a target node position.
         * Dispatches to the appropriate type-specific validation method.
         */
        void validateNestedType(GraphQLType type, Node targetNode) {
            GraphQLType unwrapped = GraphQLTypeUtil.unwrapAll(type);

            if (unwrapped instanceof GraphQLObjectType objectType) {
                validateObjectType(objectType, targetNode);
            } else if (unwrapped instanceof GraphQLInterfaceType interfaceType) {
                validateInterfaceType(interfaceType, targetNode);
            } else if (unwrapped instanceof GraphQLUnionType unionType) {
                validateUnionType(unionType, targetNode);
            }
            // Scalars and enums don't need nested validation
        }
    }
}
