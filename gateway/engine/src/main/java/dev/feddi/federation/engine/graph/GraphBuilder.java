package dev.feddi.federation.engine.graph;

import dev.feddi.federation.engine.Constants;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.parser.FieldSelectionMap.FieldSelectionSet;
import dev.feddi.federation.engine.parser.FieldSelectionMap.SelectedValue;
import dev.feddi.federation.engine.parser.FieldSelectionMapParser;
import dev.feddi.federation.engine.parser.InvalidSyntaxException;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.StringValue;
import graphql.language.Type;
import graphql.language.TypeName;
import graphql.introspection.Introspection;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLAppliedDirectiveArgument;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnionType;
import graphql.schema.idl.ScalarInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.feddi.federation.engine.compose.FederationDirectives.EXTERNAL;
import static dev.feddi.federation.engine.compose.FederationDirectives.IS;
import static dev.feddi.federation.engine.compose.FederationDirectives.LOOKUP;
import static dev.feddi.federation.engine.compose.FederationDirectives.PROVIDES;
import static dev.feddi.federation.engine.compose.FederationDirectives.REQUIRE;

/**
 * Builds a query planning Graph from a list of Subgraphs.
 * 
 * The graph contains:
 * - Nodes: One node per (type, subgraph) combination
 * - FieldMoveEdges: For fields that can be resolved within a subgraph
 * - LookupMoveEdges: For @lookup fields that enable cross-subgraph resolution
 */
public final class GraphBuilder {
    
    private static final int FIELD_MOVE_COST = 1;
    private static final int LOOKUP_MOVE_COST = 10;
    
    /**
     * Builds a Graph from the given subgraphs.
     *
     * @param subgraphs the list of subgraphs to build the graph from
     * @return the constructed Graph
     */
    public Graph build(List<Subgraph> subgraphs) {
        Graph.GraphBuilder builder = Graph.builder();
        
        // Track all types and their subgraphs
        Map<String, Set<String>> typeToSubgraphs = new HashMap<>();
        
        // Track lookup fields for cross-subgraph resolution
        List<LookupInfo> lookupFields = new ArrayList<>();
        
        // Track @require directives on entity fields (per type+subgraph)
        Map<TypeSubgraphKey, List<Requirement>> typeRequirements = new HashMap<>();
        
        // Track @provides - fields that become available when accessed through specific fields
        List<ProvidesInfo> providesList = new ArrayList<>();
        
        // First pass: collect all types, fields, lookups, requirements, and provides
        for (Subgraph subgraph : subgraphs) {
            processSubgraph(subgraph, builder, typeToSubgraphs, lookupFields, typeRequirements, providesList);
        }
        
        // Second pass: register @provides information with the graph
        registerProvidesInfo(builder, providesList);
        
        // Third pass: create lookup edges for cross-subgraph resolution
        createLookupEdges(builder, subgraphs, lookupFields, typeRequirements);
        
        // Set root nodes for Query and Mutation types
        setRootNodes(builder, subgraphs);

        return builder.build();
    }
    
    private void processSubgraph(Subgraph subgraph, Graph.GraphBuilder builder,
            Map<String, Set<String>> typeToSubgraphs,
            List<LookupInfo> lookupFields,
            Map<TypeSubgraphKey, List<Requirement>> typeRequirements,
            List<ProvidesInfo> providesList) {
        
        GraphQLSchema schema = subgraph.schema();
        String subgraphName = subgraph.name();
        
        for (GraphQLNamedType type : schema.getAllTypesAsList()) {
            if (type instanceof GraphQLObjectType objectType && !isBuiltInType(objectType.getName())) {
                // Track that this type exists in this subgraph
                typeToSubgraphs
                    .computeIfAbsent(objectType.getName(), k -> new HashSet<>())
                    .add(subgraphName);

                // Create a node for this type in this subgraph
                Node node = new Node(objectType.getName(), subgraphName);
                builder.addNode(node);

                // Register interface implementations for this type
                List<? extends GraphQLNamedType> interfaces = objectType.getInterfaces();
                if (!interfaces.isEmpty()) {
                    Set<String> interfaceNames = new HashSet<>();
                    for (GraphQLNamedType iface : interfaces) {
                        interfaceNames.add(iface.getName());
                    }
                    builder.addTypeImplementsInterfaces(objectType.getName(), interfaceNames);
                }

                // Process fields and collect @require directives on entity fields
                for (GraphQLFieldDefinition field : objectType.getFieldDefinitions()) {
                    processField(objectType.getName(), field, subgraphName, builder, lookupFields, providesList);

                    // Collect @require directives from regular fields.
                    // Note: @require is NOT allowed on @lookup fields. @lookup fields use @is to map
                    // arguments to source fields. @require is used on regular fields to declare
                    // dependencies on data from other schemas that must be fetched before resolving.
                    if (!field.hasAppliedDirective(LOOKUP)) {
                        List<Requirement> fieldRequirements = extractRequireFields(field);
                        if (!fieldRequirements.isEmpty()) {
                            TypeSubgraphKey key = new TypeSubgraphKey(objectType.getName(), subgraphName);
                            typeRequirements.computeIfAbsent(key, k -> new ArrayList<>())
                                .addAll(fieldRequirements);
                        }
                    }
                }
            } else if (type instanceof GraphQLInterfaceType interfaceType && !isBuiltInType(interfaceType.getName())) {
                // Also track interface types for cross-subgraph lookups
                typeToSubgraphs
                    .computeIfAbsent(interfaceType.getName(), k -> new HashSet<>())
                    .add(subgraphName);

                // Create a node for this interface in this subgraph
                Node node = new Node(interfaceType.getName(), subgraphName);
                builder.addNode(node);

                // Register interface-to-interface implementations (e.g., Bar implements Foo)
                // This allows the planner to find fields on implementing interfaces
                List<? extends GraphQLNamedType> parentInterfaces = interfaceType.getInterfaces();
                if (!parentInterfaces.isEmpty()) {
                    Set<String> parentInterfaceNames = new HashSet<>();
                    for (GraphQLNamedType parentIface : parentInterfaces) {
                        parentInterfaceNames.add(parentIface.getName());
                    }
                    builder.addTypeImplementsInterfaces(interfaceType.getName(), parentInterfaceNames);
                }

                // Process fields on the interface
                for (GraphQLFieldDefinition field : interfaceType.getFieldDefinitions()) {
                    processField(interfaceType.getName(), field, subgraphName, builder, lookupFields, providesList);

                    // Collect @require directives from regular fields.
                    // Note: @require is NOT allowed on @lookup fields (see comment above).
                    if (!field.hasAppliedDirective(LOOKUP)) {
                        List<Requirement> fieldRequirements = extractRequireFields(field);
                        if (!fieldRequirements.isEmpty()) {
                            TypeSubgraphKey key = new TypeSubgraphKey(interfaceType.getName(), subgraphName);
                            typeRequirements.computeIfAbsent(key, k -> new ArrayList<>())
                                .addAll(fieldRequirements);
                        }
                    }
                }
            } else if (type instanceof GraphQLUnionType unionType && !isBuiltInType(unionType.getName())) {
                // Track union types for cross-subgraph lookups
                typeToSubgraphs
                    .computeIfAbsent(unionType.getName(), k -> new HashSet<>())
                    .add(subgraphName);

                // Create a node for this union in this subgraph
                Node node = new Node(unionType.getName(), subgraphName);
                builder.addNode(node);

                // Register each member type as belonging to this union
                for (GraphQLNamedType memberType : unionType.getTypes()) {
                    builder.addTypeMemberOfUnions(memberType.getName(), Set.of(unionType.getName()));
                }
            }
        }
    }
    
    private void processField(String parentTypeName, GraphQLFieldDefinition field,
            String subgraphName, Graph.GraphBuilder builder, List<LookupInfo> lookupFields,
            List<ProvidesInfo> providesList) {

        Node sourceNode = new Node(parentTypeName, subgraphName);
        GraphQLOutputType fieldType = field.getType();
        String targetTypeName = GraphQLTypeUtil.unwrapAll(fieldType).getName();

        // Skip @external fields - they can't be resolved directly by this subgraph
        boolean isExternal = field.hasAppliedDirective(EXTERNAL);

        // Create FieldMoveEdge for this field (unless it's @external)
        if (!isExternal) {
            if (GraphQLTypeUtil.isLeaf(fieldType)) {
                // Scalar fields: self-referential edge (stays on same node)
                builder.addFieldEdge(field.getName(), sourceNode, sourceNode, FIELD_MOVE_COST);
            } else {
                // Object type fields: edge to the target type node
                Node targetNode = new Node(targetTypeName, subgraphName);
                builder.addFieldEdge(field.getName(), sourceNode, targetNode, FIELD_MOVE_COST);

                // Track @provides - fields that become available when accessed through this field
                if (field.hasAppliedDirective(PROVIDES)) {
                    GraphQLAppliedDirective providesDirective = field.getAppliedDirective(PROVIDES);
                    GraphQLAppliedDirectiveArgument fieldsArg = providesDirective.getArgument("fields");
                    String providedFieldsStr = getStringValue(fieldsArg);
                    if (providedFieldsStr != null) {
                        FieldSelectionSet selectionSet = parseFieldSelectionSet(providedFieldsStr);
                        providesList.add(new ProvidesInfo(sourceNode, targetNode, field.getName(), selectionSet));
                    }
                }
            }
        }

        // Check for @lookup directive
        // Note: @lookup fields use @is to map arguments to lookup arguments.
        // @require is NOT used on @lookup fields - it's only for entity fields.
        if (field.hasAppliedDirective(LOOKUP)) {
            List<LookupArgument> lookupArguments = extractLookupArguments(field);

            lookupFields.add(new LookupInfo(
                subgraphName,
                parentTypeName,
                field.getName(),
                targetTypeName,
                lookupArguments
            ));
        }
    }

    private void createLookupEdges(Graph.GraphBuilder builder, List<Subgraph> subgraphs,
            List<LookupInfo> lookupFields,
            Map<TypeSubgraphKey, List<Requirement>> typeRequirements) {

        // Build a map of subgraph name to Subgraph for quick lookup
        Map<String, Subgraph> subgraphByName = new HashMap<>();
        for (Subgraph sg : subgraphs) {
            subgraphByName.put(sg.name(), sg);
        }

        // For each lookup, create edges from all subgraphs that have the target type
        // to the subgraph providing the lookup
        for (LookupInfo lookup : lookupFields) {
            String targetTypeName = lookup.targetTypeName();
            if (targetTypeName == null) continue;

            // Get the target subgraph's schema to check for implementations
            Subgraph targetSubgraph = subgraphByName.get(lookup.subgraphName());
            if (targetSubgraph == null) continue;

            // Find all subgraphs that have this target type
            for (Subgraph subgraph : subgraphs) {
                String sourceSubgraph = subgraph.name();

                // Don't create a lookup edge to the same subgraph
                if (sourceSubgraph.equals(lookup.subgraphName())) continue;

                // Check if this subgraph has the target type (can be object, interface, or union)
                GraphQLNamedType sourceType = (GraphQLNamedType) subgraph.schema()
                    .getType(targetTypeName);
                if (sourceType == null) continue;
                // Only process object, interface, and union types
                if (!(sourceType instanceof GraphQLObjectType)
                    && !(sourceType instanceof GraphQLInterfaceType)
                    && !(sourceType instanceof GraphQLUnionType)) {
                    continue;
                }

                Node sourceNode = new Node(targetTypeName, sourceSubgraph);

                // Lookup arguments come from @is directives (or implicit @is using argument names)
                List<LookupArgument> lookupArguments = lookup.lookupArguments();

                // Gather requirements from target type's entity fields.
                // Note: @require is NOT on @lookup fields - it's on entity fields in the target subgraph.
                // These requirements specify data dependencies that must be fetched before resolving
                // those entity fields after the lookup.
                TypeSubgraphKey targetKey = new TypeSubgraphKey(targetTypeName, lookup.subgraphName());
                List<Requirement> allRequirements = typeRequirements.getOrDefault(targetKey, List.of());

                // Create edge to the target type itself
                Node targetNode = new Node(targetTypeName, lookup.subgraphName());
                builder.addEdge(new LookupMoveEdge(
                    lookup.fieldName(),
                    sourceNode,
                    targetNode,
                    LOOKUP_MOVE_COST,
                    lookupArguments,
                    allRequirements
                ));

                // If the target type is an interface, also create edges to implementing types
                // This enables cross-subgraph inline fragment resolution:
                // e.g., from Content/content we can reach Article/analytics via lookup
                GraphQLNamedType targetType = (GraphQLNamedType) targetSubgraph.schema()
                    .getType(targetTypeName);
                if (targetType instanceof GraphQLInterfaceType interfaceType) {
                    List<GraphQLObjectType> implementations = targetSubgraph.schema()
                        .getImplementations(interfaceType);

                    for (GraphQLObjectType implType : implementations) {
                        String implTypeName = implType.getName();
                        Node implTargetNode = new Node(implTypeName, lookup.subgraphName());

                        // Create edge from interface source to implementation target
                        // Uses the same lookup field and arguments (implementations inherit the key)
                        builder.addEdge(new LookupMoveEdge(
                            lookup.fieldName(),
                            sourceNode,
                            implTargetNode,
                            LOOKUP_MOVE_COST,
                            lookupArguments,
                            allRequirements
                        ));
                    }
                } else if (targetType instanceof GraphQLUnionType unionType) {
                    // If the target type is a union, also create edges to member types
                    // This enables cross-subgraph inline fragment resolution for unions:
                    // e.g., from Media/content (union) we can reach Book/ratings via lookup
                    for (GraphQLNamedType memberType : unionType.getTypes()) {
                        String memberTypeName = memberType.getName();
                        Node memberTargetNode = new Node(memberTypeName, lookup.subgraphName());

                        // Create edge from union source to member target
                        // Uses the same lookup field and arguments
                        builder.addEdge(new LookupMoveEdge(
                            lookup.fieldName(),
                            sourceNode,
                            memberTargetNode,
                            LOOKUP_MOVE_COST,
                            lookupArguments,
                            allRequirements
                        ));
                    }
                }
            }
        }
    }
    
    /**
     * Special subgraph name for the unified root nodes.
     * These nodes aggregate all Query/Mutation fields from all subgraphs.
     */
    public static final String ROOT_SUBGRAPH = "$root";

    /**
     * Sets root nodes for the graph (Query and Mutation).
     *
     * Creates unified root nodes that have edges to all fields of that type
     * from all subgraphs. This allows the PathFinder to reach any field
     * regardless of which subgraph defines it.
     */
    private void setRootNodes(Graph.GraphBuilder builder, List<Subgraph> subgraphs) {
        // Set up Query root
        setRootForType(builder, subgraphs, Constants.QUERY, schema -> schema.getQueryType());

        // Set up Mutation root (optional - only if any subgraph has mutations)
        setRootForType(builder, subgraphs, Constants.MUTATION, schema -> schema.getMutationType());
    }

    /**
     * Creates a unified root node for a specific operation type (Query or Mutation).
     */
    private void setRootForType(Graph.GraphBuilder builder, List<Subgraph> subgraphs,
                                String typeName, java.util.function.Function<GraphQLSchema, GraphQLObjectType> typeExtractor) {
        boolean foundType = false;
        Node unifiedRoot = null;

        for (Subgraph subgraph : subgraphs) {
            GraphQLObjectType type = typeExtractor.apply(subgraph.schema());
            if (type != null) {
                // Create unified root node on first occurrence
                if (unifiedRoot == null) {
                    unifiedRoot = new Node(typeName, ROOT_SUBGRAPH);
                    builder.addNode(unifiedRoot);
                    builder.root(unifiedRoot);
                }
                foundType = true;

                // Create a subgraph-specific root node for scalar fields
                // This allows the planner to know which subgraph to call
                Node subgraphRootNode = new Node(typeName, subgraph.name());
                builder.addNode(subgraphRootNode);

                for (GraphQLFieldDefinition field : type.getFieldDefinitions()) {
                    GraphQLOutputType fieldType = field.getType();
                    if (GraphQLTypeUtil.isLeaf(fieldType)) {
                        // Scalar/enum fields: edge to subgraph-specific root node
                        // This ensures the planner routes to the correct subgraph
                        builder.addFieldEdge(field.getName(), unifiedRoot, subgraphRootNode, FIELD_MOVE_COST);
                    } else {
                        // Object type fields: edge to the target type node in the subgraph
                        String targetTypeName = GraphQLTypeUtil.unwrapAll(fieldType).getName();
                        Node targetNode = new Node(targetTypeName, subgraph.name());
                        builder.addFieldEdge(field.getName(), unifiedRoot, targetNode, FIELD_MOVE_COST);
                    }
                }
            }
        }

        // Query type is required, Mutation type is optional
        if (!foundType && Constants.QUERY.equals(typeName)) {
            throw new IllegalStateException("No Query type found in any subgraph");
        }
    }
    
    /**
     * Extracts key field requirements from lookup field arguments.
     *
     * Arguments with @is directive use the directive's field value as the fieldPath.
     * Arguments without @is (and without @require) default to using the argument name as fieldPath.
     * This allows omitting @is when the argument name matches the key field name.
     *
     * Per the federation spec, ALL arguments on a @lookup field form part of the stable key
     * (except @require arguments which are dependency requirements).
     */
    private List<LookupArgument> extractLookupArguments(GraphQLFieldDefinition field) {
        List<LookupArgument> lookupArguments = new ArrayList<>();

        for (GraphQLArgument arg : field.getArguments()) {
            Type<?> argType = convertToLanguageType(arg.getType());

            if (arg.hasAppliedDirective(IS)) {
                // Use @is directive's field value as the fieldPath
                GraphQLAppliedDirective isDirective = arg.getAppliedDirective(IS);
                GraphQLAppliedDirectiveArgument fieldArg = isDirective.getArgument("field");
                String fieldValue = getStringValue(fieldArg);
                if (fieldValue != null) {
                    lookupArguments.add(LookupArgument.of(arg.getName(), fieldValue, argType));
                }
            } else {
                // No @is directive - use argument name as fieldPath (implicit @is)
                lookupArguments.add(LookupArgument.of(arg.getName(), arg.getName(), argType));
            }
        }

        return lookupArguments;
    }
    
    private List<Requirement> extractRequireFields(GraphQLFieldDefinition field) {
        List<Requirement> requirements = new ArrayList<>();
        String fieldName = field.getName();

        for (GraphQLArgument arg : field.getArguments()) {
            if (arg.hasAppliedDirective(REQUIRE)) {
                GraphQLAppliedDirective requireDirective = arg.getAppliedDirective(REQUIRE);
                GraphQLAppliedDirectiveArgument fieldArg = requireDirective.getArgument("field");
                String fieldSelectionMap = getStringValue(fieldArg);
                if (fieldSelectionMap != null) {
                    try {
                        SelectedValue selection = FieldSelectionMapParser.parseFieldSelectionMap(fieldSelectionMap);
                        Type<?> argType = convertToLanguageType(arg.getType());
                        boolean fieldReturnNonNull = GraphQLTypeUtil.isNonNull(field.getType());
                        // Include the field name so the planner knows which field this requirement belongs to
                        requirements.add(Requirement.of(arg.getName(), selection, argType, fieldName, fieldReturnNonNull));
                    } catch (InvalidSyntaxException e) {
                        throw new IllegalArgumentException(
                            "Invalid @require field selection on argument '" + arg.getName() +
                            "': " + e.getMessage(), e);
                    }
                }
            }
        }

        return requirements;
    }
    
    /**
     * Extracts a string value from a directive argument.
     * For FieldSelectionMap arguments (custom scalar), the value is a StringValue AST node.
     */
    private String getStringValue(GraphQLAppliedDirectiveArgument arg) {
        if (arg == null) return null;
        Object value = arg.getValue();
        if (value instanceof StringValue stringValue) {
            return stringValue.getValue();
        }
        return value != null ? value.toString() : null;
    }
    
    /**
     * Parses a FieldSelectionSet string (used by @provides and @key directives).
     * Returns the full FieldSelectionSet structure to preserve nested selections and type conditions.
     */
    private FieldSelectionSet parseFieldSelectionSet(String fieldsStr) {
        try {
            return FieldSelectionMapParser.parseFieldSelectionSet(fieldsStr);
        } catch (InvalidSyntaxException e) {
            throw new IllegalArgumentException(
                "Invalid @provides field selection: " + e.getMessage(), e);
        }
    }

    private boolean isBuiltInType(String typeName) {
        return Introspection.isIntrospectionTypes(typeName) || ScalarInfo.isGraphqlSpecifiedScalar(typeName);
    }

    /**
     * Converts a GraphQL runtime type (GraphQLInputType) to an AST type (graphql.language.Type).
     */
    private Type<?> convertToLanguageType(GraphQLInputType graphqlType) {
        if (graphqlType instanceof GraphQLNonNull nonNull) {
            Type<?> wrappedType = convertToLanguageType((GraphQLInputType) nonNull.getWrappedType());
            return NonNullType.newNonNullType(wrappedType).build();
        }
        if (graphqlType instanceof GraphQLList list) {
            Type<?> wrappedType = convertToLanguageType((GraphQLInputType) list.getWrappedType());
            return ListType.newListType(wrappedType).build();
        }
        if (graphqlType instanceof GraphQLNamedType named) {
            return TypeName.newTypeName(named.getName()).build();
        }
        return null;
    }

    /**
     * Information about a @lookup field.
     *
     * A @lookup field defines its return type as an "entity" - a type that can be
     * resolved across subgraphs. The @is directive maps lookup arguments to source
     * fields on the entity.
     *
     * Note: @lookup fields do NOT have @require directives. @require is used on
     * regular fields to declare dependencies on data from other schemas.
     */
    private record LookupInfo(
        String subgraphName,
        String parentTypeName,
        String fieldName,
        String targetTypeName,
        List<LookupArgument> lookupArguments
    ) {}
    
    /**
     * Key for identifying a type within a specific subgraph.
     */
    private record TypeSubgraphKey(String typeName, String subgraphName) {}
    
    /**
     * Information about @provides on a field.
     */
    private record ProvidesInfo(
        Node sourceNode,
        Node targetNode,
        String fieldName,
        FieldSelectionSet providedFields
    ) {}
    
    /**
     * Registers @provides information with the graph.
     * This associates field edges with the fields they provide, allowing the PathFinder
     * to resolve those fields when traversing through the providing field.
     */
    private void registerProvidesInfo(Graph.GraphBuilder builder, List<ProvidesInfo> providesList) {
        for (ProvidesInfo info : providesList) {
            // Find the field edge for this providing field
            FieldMoveEdge fieldEdge = new FieldMoveEdge(
                info.fieldName(), 
                info.sourceNode(), 
                info.targetNode(), 
                FIELD_MOVE_COST
            );
            builder.addProvides(fieldEdge, info.providedFields());
        }
    }
}
