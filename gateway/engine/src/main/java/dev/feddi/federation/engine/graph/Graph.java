package dev.feddi.federation.engine.graph;

import dev.feddi.federation.engine.parser.FieldSelectionMap.FieldSelection;
import dev.feddi.federation.engine.parser.FieldSelectionMap.FieldSelectionSet;
import dev.feddi.federation.engine.parser.FieldSelectionMap.InlineFragment;
import dev.feddi.federation.engine.parser.FieldSelectionMap.SelectionItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Represents the query planning graph.
 * Contains nodes (type instances in subgraphs) and edges (possible transitions).
 */
public final class Graph {

    private final Set<Node> nodes;
    private final List<Edge> edges;
    private final Map<Node, List<Edge>> outgoingEdges;
    // Maps operation type name (e.g., "Query", "Mutation") to root node
    private final Map<String, Node> rootNodes;
    // Maps a field edge to the FieldSelectionSet it @provides on the target node
    private final Map<FieldMoveEdge, FieldSelectionSet> providesMap;
    // Maps type name to the interfaces it implements (across all subgraphs)
    private final Map<String, Set<String>> typeImplementsInterfaces;
    // Maps interface name to the types that implement it (across all subgraphs)
    private final Map<String, Set<String>> interfaceToImplementingTypes;
    // Maps type name to the unions it is a member of (across all subgraphs)
    private final Map<String, Set<String>> typeMemberOfUnions;

    private Graph(Set<Node> nodes, List<Edge> edges, Map<String, Node> rootNodes,
                  Map<FieldMoveEdge, FieldSelectionSet> providesMap, Map<String, Set<String>> typeImplementsInterfaces,
                  Map<String, Set<String>> interfaceToImplementingTypes,
                  Map<String, Set<String>> typeMemberOfUnions) {
        this.nodes = Set.copyOf(nodes);
        this.edges = List.copyOf(edges);
        this.rootNodes = Map.copyOf(rootNodes);
        this.providesMap = Map.copyOf(providesMap);
        this.typeImplementsInterfaces = Map.copyOf(typeImplementsInterfaces);
        this.interfaceToImplementingTypes = Map.copyOf(interfaceToImplementingTypes);
        this.typeMemberOfUnions = Map.copyOf(typeMemberOfUnions);

        // Build adjacency list for efficient edge lookup
        this.outgoingEdges = new HashMap<>();
        for (Node node : nodes) {
            outgoingEdges.put(node, new ArrayList<>());
        }
        for (Edge edge : edges) {
            outgoingEdges.get(edge.source()).add(edge);
        }
    }
    
    /**
     * Gets all nodes in the graph.
     */
    public Set<Node> nodes() {
        return nodes;
    }
    
    /**
     * Gets all edges in the graph.
     */
    public List<Edge> edges() {
        return edges;
    }
    
    /**
     * Gets the root node for a specific operation type (e.g., "Query", "Mutation").
     *
     * @param operationType the operation type name
     * @return the root node for that operation type, or null if not found
     */
    public Node getRootNode(String operationType) {
        return rootNodes.get(operationType);
    }

    /**
     * Gets all outgoing edges from a node.
     */
    public List<Edge> edgesFrom(Node node) {
        return outgoingEdges.getOrDefault(node, List.of());
    }
    
    /**
     * Gets all FieldMoveEdges from a node.
     */
    public Stream<FieldMoveEdge> fieldEdgesFrom(Node node) {
        return edgesFrom(node).stream()
            .filter(e -> e instanceof FieldMoveEdge)
            .map(e -> (FieldMoveEdge) e);
    }
    
    /**
     * Gets all LookupMoveEdges from a node.
     */
    public Stream<LookupMoveEdge> lookupEdgesFrom(Node node) {
        return edgesFrom(node).stream()
            .filter(e -> e instanceof LookupMoveEdge)
            .map(e -> (LookupMoveEdge) e);
    }
    
    /**
     * Finds a FieldMoveEdge for a specific field from a node.
     */
    public Optional<FieldMoveEdge> findFieldEdge(Node node, String fieldName) {
        return fieldEdgesFrom(node)
            .filter(e -> e.fieldName().equals(fieldName))
            .findFirst();
    }
    
    /**
     * Finds all LookupMoveEdges from a node to a specific subgraph.
     */
    public Stream<LookupMoveEdge> findLookupEdgesToSubgraph(Node node, String subgraph) {
        return lookupEdgesFrom(node)
            .filter(e -> e.target().subgraph().equals(subgraph));
    }
    
    /**
     * Gets all unique subgraphs in the graph.
     */
    public Set<String> subgraphs() {
        Set<String> result = new HashSet<>();
        for (Node node : nodes) {
            result.add(node.subgraph());
        }
        return result;
    }
    
    /**
     * Finds a node by type name and subgraph.
     */
    public Optional<Node> findNode(String typeName, String subgraph) {
        return nodes.stream()
            .filter(n -> n.typeName().equals(typeName) && n.subgraph().equals(subgraph))
            .findFirst();
    }
    
    /**
     * Checks if the graph contains a specific node.
     */
    public boolean containsNode(Node node) {
        return nodes.contains(node);
    }
    
    /**
     * Gets the FieldSelectionSet provided by a field edge via @provides directive.
     * @param edge the field edge to check
     * @return the FieldSelectionSet provided by this edge, or empty selection set if none
     */
    public FieldSelectionSet getProvidedFields(FieldMoveEdge edge) {
        return providesMap.getOrDefault(edge, new FieldSelectionSet(List.of()));
    }

    /**
     * Checks if a field edge provides a specific field.
     * Handles both direct field matches and fields within inline fragments.
     * @param edge the field edge to check
     * @param fieldName the field name to look for
     * @param typeContext the current type context (from inline fragment), may be null
     * @return true if the field is provided
     */
    public boolean providesField(FieldMoveEdge edge, String fieldName, String typeContext) {
        FieldSelectionSet selectionSet = getProvidedFields(edge);
        return isFieldProvided(selectionSet.items(), fieldName, typeContext);
    }

    /**
     * Checks if a field is provided by searching the selection set.
     * Handles both direct field matches and fields within inline fragments.
     */
    private boolean isFieldProvided(List<SelectionItem> items, String fieldName, String typeContext) {
        for (SelectionItem item : items) {
            switch (item) {
                case FieldSelection field -> {
                    if (field.fieldName().equals(fieldName)) {
                        return true;
                    }
                }
                case InlineFragment fragment -> {
                    // Check if type condition matches (or typeContext is null - match all)
                    if (typeContext == null || fragment.typeName().equals(typeContext)) {
                        if (isFieldProvided(fragment.selections(), fieldName, typeContext)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Gets the interfaces that a type implements.
     * @param typeName the type name to check
     * @return set of interface names that this type implements, or empty set if none
     */
    public Set<String> getInterfacesForType(String typeName) {
        return typeImplementsInterfaces.getOrDefault(typeName, Set.of());
    }

    /**
     * Gets the types that implement an interface.
     * @param interfaceName the interface name to check
     * @return set of type names that implement this interface, or empty set if none
     */
    public Set<String> getImplementingTypesForInterface(String interfaceName) {
        return interfaceToImplementingTypes.getOrDefault(interfaceName, Set.of());
    }

    /**
     * Gets the unions that a type is a member of.
     * @param typeName the type name to check
     * @return set of union names that this type is a member of, or empty set if none
     */
    public Set<String> getUnionsForType(String typeName) {
        return typeMemberOfUnions.getOrDefault(typeName, Set.of());
    }

    /**
     * Checks if a field on a type has @require dependencies.
     * This is determined by checking if any LookupMoveEdge targeting that type/subgraph
     * has requirements for the given field.
     *
     * @param typeName the type name
     * @param subgraph the subgraph
     * @param fieldName the field name to check
     * @return true if the field has @require, false otherwise
     */
    public boolean fieldHasRequire(String typeName, String subgraph, String fieldName) {
        // Check all lookup edges that target this type/subgraph
        for (Edge edge : edges) {
            if (edge instanceof LookupMoveEdge lookupEdge) {
                if (lookupEdge.target().typeName().equals(typeName)
                    && lookupEdge.target().subgraph().equals(subgraph)) {
                    // Check if this lookup has @require for the field
                    for (Requirement req : lookupEdge.requires()) {
                        if (fieldName.equals(req.fieldName())) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Creates a new GraphBuilder.
     */
    public static GraphBuilder builder() {
        return new GraphBuilder();
    }
    
    @Override
    public String toString() {
        return String.format("Graph(nodes=%d, edges=%d, roots=%s)",
            nodes.size(), edges.size(), rootNodes.keySet());
    }
    
    /**
     * Builder for constructing Graph instances.
     */
    public static final class GraphBuilder {
        private final Set<Node> nodes = new HashSet<>();
        private final List<Edge> edges = new ArrayList<>();
        private final Map<FieldMoveEdge, FieldSelectionSet> providesMap = new HashMap<>();
        private final Map<String, Node> rootNodes = new HashMap<>();
        private final Map<String, Set<String>> typeImplementsInterfaces = new HashMap<>();
        private final Map<String, Set<String>> interfaceToImplementingTypes = new HashMap<>();
        private final Map<String, Set<String>> typeMemberOfUnions = new HashMap<>();

        private GraphBuilder() {}
        
        /**
         * Adds a node to the graph.
         */
        public GraphBuilder addNode(Node node) {
            nodes.add(node);
            return this;
        }
        
        /**
         * Adds a node to the graph.
         */
        public GraphBuilder addNode(String typeName, String subgraph) {
            return addNode(new Node(typeName, subgraph));
        }
        
        /**
         * Adds a root node for a specific operation type (e.g., "Query", "Mutation").
         * The operation type is derived from the node's type name.
         */
        public GraphBuilder root(Node node) {
            rootNodes.put(node.typeName(), node);
            nodes.add(node);
            return this;
        }

        /**
         * Adds a root node for a specific operation type.
         */
        public GraphBuilder root(String typeName, String subgraph) {
            return root(new Node(typeName, subgraph));
        }
        
        /**
         * Adds an edge to the graph.
         */
        public GraphBuilder addEdge(Edge edge) {
            nodes.add(edge.source());
            nodes.add(edge.target());
            edges.add(edge);
            return this;
        }
        
        /**
         * Adds a FieldMoveEdge to the graph.
         */
        public GraphBuilder addFieldEdge(String fieldName, Node source, Node target, int cost) {
            return addEdge(new FieldMoveEdge(fieldName, source, target, cost));
        }
        
        /**
         * Adds a FieldMoveEdge with default cost.
         */
        public GraphBuilder addFieldEdge(String fieldName, Node source, Node target) {
            return addEdge(FieldMoveEdge.withDefaultCost(fieldName, source, target));
        }
        
        /**
         * Adds a LookupMoveEdge to the graph.
         */
        public GraphBuilder addLookupEdge(
            String lookupField,
            Node source,
            Node target,
            int cost,
            List<LookupArgument> lookupArguments
        ) {
            return addEdge(new LookupMoveEdge(lookupField, source, target, cost, lookupArguments, List.of()));
        }

        /**
         * Adds a LookupMoveEdge with default cost.
         */
        public GraphBuilder addLookupEdge(String lookupField, Node source, Node target, List<LookupArgument> lookupArguments) {
            return addEdge(LookupMoveEdge.withDefaultCost(lookupField, source, target, lookupArguments));
        }
        
        /**
         * Associates @provides fields with a field edge.
         * @param edge the field edge that provides additional fields
         * @param providedFields the FieldSelectionSet provided when traversing this edge
         */
        public GraphBuilder addProvides(FieldMoveEdge edge, FieldSelectionSet providedFields) {
            if (providedFields != null && !providedFields.items().isEmpty()) {
                providesMap.put(edge, providedFields);
            }
            return this;
        }

        /**
         * Registers that a type implements one or more interfaces.
         * This enables the PathFinder to use interface lookup edges when at a concrete type.
         * Also maintains the reverse mapping from interfaces to implementing types.
         * @param typeName the implementing type name
         * @param interfaceNames the interfaces that this type implements
         */
        public GraphBuilder addTypeImplementsInterfaces(String typeName, Set<String> interfaceNames) {
            if (!interfaceNames.isEmpty()) {
                typeImplementsInterfaces.computeIfAbsent(typeName, k -> new HashSet<>()).addAll(interfaceNames);
                // Also populate reverse mapping
                for (String interfaceName : interfaceNames) {
                    interfaceToImplementingTypes.computeIfAbsent(interfaceName, k -> new HashSet<>()).add(typeName);
                }
            }
            return this;
        }

        /**
         * Registers that a type is a member of one or more unions.
         * This enables the PathFinder to use union lookup edges when at a concrete type.
         * @param typeName the member type name
         * @param unionNames the unions that this type is a member of
         */
        public GraphBuilder addTypeMemberOfUnions(String typeName, Set<String> unionNames) {
            if (!unionNames.isEmpty()) {
                typeMemberOfUnions.computeIfAbsent(typeName, k -> new HashSet<>()).addAll(unionNames);
            }
            return this;
        }

        /**
         * Builds the graph.
         */
        public Graph build() {
            if (rootNodes.isEmpty()) {
                throw new IllegalStateException("At least one root node must be set");
            }
            for (Node rootNode : rootNodes.values()) {
                if (!nodes.contains(rootNode)) {
                    throw new IllegalStateException("Root node " + rootNode + " must be in the graph");
                }
            }
            return new Graph(nodes, edges, rootNodes, providesMap, typeImplementsInterfaces,
                interfaceToImplementingTypes, typeMemberOfUnions);
        }
    }
}
