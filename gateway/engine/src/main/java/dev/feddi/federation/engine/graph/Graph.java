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
import java.util.TreeSet;
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
    // Return types of @external fields per node ("Level1/a" -> level2 -> "Level2"), for fields reached via @provides
    private final Map<Node, Map<String, String>> externalFieldTypes;
    // The @require arguments of each node's fields, by field name
    private final Map<Node, Map<String, List<Requirement>>> requirements;
    // The output type of each node's fields as GraphQL prints it ("[String!]"), by field name
    private final Map<Node, Map<String, String>> fieldTypes;

    private Graph(Set<Node> nodes, List<Edge> edges, Map<String, Node> rootNodes,
                  Map<FieldMoveEdge, FieldSelectionSet> providesMap, Map<String, Set<String>> typeImplementsInterfaces,
                  Map<String, Set<String>> interfaceToImplementingTypes,
                  Map<String, Set<String>> typeMemberOfUnions, Map<Node, Map<String, String>> externalFieldTypes,
                  Map<Node, Map<String, List<Requirement>>> requirements, Map<Node, Map<String, String>> fieldTypes) {
        this.nodes = Set.copyOf(nodes);
        this.edges = List.copyOf(edges);
        this.rootNodes = Map.copyOf(rootNodes);
        this.providesMap = Map.copyOf(providesMap);
        this.typeImplementsInterfaces = Map.copyOf(typeImplementsInterfaces);
        this.interfaceToImplementingTypes = Map.copyOf(interfaceToImplementingTypes);
        this.typeMemberOfUnions = Map.copyOf(typeMemberOfUnions);
        this.externalFieldTypes = Map.copyOf(externalFieldTypes);
        this.requirements = Map.copyOf(requirements);
        this.fieldTypes = Map.copyOf(fieldTypes);

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
     * The named return type of an @external field of {@code source}, or null.
     */
    public String externalFieldType(Node source, String fieldName) {
        return externalFieldTypes.getOrDefault(source, Map.of()).get(fieldName);
    }

    /**
     * Gets the FieldSelectionSet provided by a field edge via @provides directive.
     * @param edge the field edge to check
     * @return the FieldSelectionSet provided by this edge, or empty selection set if none
     */
    public FieldSelectionSet getProvidedFields(FieldMoveEdge edge) {
        FieldSelectionSet provided = providesMap.get(edge);
        if (provided == null && edge.source().subgraph().equals(dev.feddi.federation.engine.graph.GraphBuilder.ROOT_SUBGRAPH)) {
            // A root field is reached from the unified root node; @provides is on the subgraph's own edge
            for (var entry : providesMap.entrySet()) {
                FieldMoveEdge own = entry.getKey();
                if (own.fieldName().equals(edge.fieldName()) && own.target().equals(edge.target())
                    && own.source().typeName().equals(edge.source().typeName())) {
                    provided = entry.getValue();
                }
            }
        }
        return provided != null ? provided : new FieldSelectionSet(List.of());
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
     * Gets the member types of a union.
     * @param unionName the union name
     * @return set of member type names, or empty set if the union is unknown
     */
    public Set<String> getUnionMembers(String unionName) {
        Set<String> members = new TreeSet<>();
        typeMemberOfUnions.forEach((type, unions) -> {
            if (unions.contains(unionName)) {
                members.add(type);
            }
        });
        return members;
    }

    /**
     * Whether a field of a type in a subgraph has @require arguments. An interface field counts as requiring when
     * an implementation's field in the subgraph does: it is then resolved per possible type.
     */
    public boolean fieldHasRequire(String typeName, String subgraph, String fieldName) {
        if (!requirementsOf(typeName, subgraph, fieldName).isEmpty()) {
            return true;
        }
        for (String implementation : getImplementingTypesForInterface(typeName)) {
            if (fieldHasRequire(implementation, subgraph, fieldName)) {
                return true;
            }
        }
        return false;
    }

    /** The @require arguments of a field of a type in a subgraph. */
    public List<Requirement> requirementsOf(String typeName, String subgraph, String fieldName) {
        return requirements.getOrDefault(new Node(typeName, subgraph), Map.of()).getOrDefault(fieldName, List.of());
    }

    /**
     * The object types an object of {@code typeName} can have: an interface's implementations, a union's members,
     * or the type itself; empty for null.
     */
    public Set<String> concreteTypes(String typeName) {
        if (typeName == null) {
            return Set.of();
        }
        Set<String> implementations = getImplementingTypesForInterface(typeName);
        if (!implementations.isEmpty()) {
            return implementations;
        }
        Set<String> members = getUnionMembers(typeName);
        return members.isEmpty() ? Set.of(typeName) : members;
    }

    /** Whether the type is an interface with implementations or a union with members. */
    public boolean isAbstract(String typeName) {
        return !getImplementingTypesForInterface(typeName).isEmpty() || !getUnionMembers(typeName).isEmpty();
    }

    /** Whether the type is the root type of an operation (Query, Mutation). */
    public boolean isRootOperationType(String typeName) {
        return rootNodes.containsKey(typeName);
    }

    /**
     * The output type of a field of a type in a subgraph as GraphQL prints it (e.g. {@code [String!]}), or null if
     * the subgraph does not define the field.
     */
    public String fieldType(String typeName, String subgraph, String fieldName) {
        return fieldTypes.getOrDefault(new Node(typeName, subgraph), Map.of()).get(fieldName);
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
        private final Map<Node, Map<String, String>> externalFieldTypes = new HashMap<>();
        private final Map<Node, Map<String, List<Requirement>>> requirements = new HashMap<>();
        private final Map<Node, Map<String, String>> fieldTypes = new HashMap<>();

        private GraphBuilder() {}

        /** Records a field's @require arguments. */
        public GraphBuilder addRequirements(Node node, List<Requirement> fieldRequirements) {
            for (Requirement requirement : fieldRequirements) {
                requirements.computeIfAbsent(node, k -> new HashMap<>())
                    .computeIfAbsent(requirement.fieldName(), k -> new ArrayList<>()).add(requirement);
            }
            return this;
        }

        /** Records a field's output type as GraphQL prints it. */
        public GraphBuilder addFieldType(Node node, String fieldName, String printedType) {
            fieldTypes.computeIfAbsent(node, k -> new HashMap<>()).put(fieldName, printedType);
            return this;
        }

        /**
         * Records the (named) return type of an @external field, which has no field edge.
         */
        public GraphBuilder addExternalFieldType(Node source, String fieldName, String typeName) {
            externalFieldTypes.computeIfAbsent(source, k -> new HashMap<>()).put(fieldName, typeName);
            return this;
        }
        
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
            return new Graph(nodes, edges, rootNodes, providesMap, typeImplementsInterfaces,
                interfaceToImplementingTypes, typeMemberOfUnions, externalFieldTypes, requirements, fieldTypes);
        }
    }
}
