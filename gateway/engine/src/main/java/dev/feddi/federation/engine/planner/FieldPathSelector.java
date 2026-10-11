package dev.feddi.federation.engine.planner;

import dev.feddi.federation.engine.IntrospectionFields;
import dev.feddi.federation.engine.graph.Graph;
import dev.feddi.federation.engine.graph.Node;
import dev.feddi.federation.engine.parser.FieldSelectionMap.Path;
import dev.feddi.federation.engine.parser.FieldSelectionMap.PathSegment;
import graphql.language.Argument;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Selects the FieldSelectionMap paths the planner needs (lookup keys, required fields) in a plan's selections:
 * nested paths, paths through type conditions and abstract types (in inline fragments), and aliases where a
 * field could not be merged with another field of its response key.
 */
final class FieldPathSelector {
    private final Graph graph;
    // The fields the client selects, by response path and response key
    private final Map<List<String>, Map<String, List<ClientField>>> clientFields;

    FieldPathSelector(Graph graph, Map<List<String>, Map<String, List<ClientField>>> clientFields) {
        this.graph = graph;
        this.clientFields = clientFields;
    }

    /**
     * A field the client selects: the type condition it is selected under (null: none, the parent field's type),
     * its name and its arguments (printed).
     */
    record ClientField(String typeCondition, String fieldName, List<String> printedArguments) {
    }

    Map<String, String> addNestedFieldPathOnly(SubgraphPlan plan, Path path, List<String> parentPath,
                                                       FieldOrigin origin, String subgraph, String startTypeName) {
        List<String> currentPath = new ArrayList<>(parentPath);
        Map<String, String> fieldToResponseKey = new LinkedHashMap<>();

        // If path has initial type condition (e.g., <Movie>.imdbCode), add __typename
        // at the start so the executor can verify the type at runtime
        if (path.hasInitialTypeCondition()) {
            plan.addArtificialField(IntrospectionFields.TYPENAME, currentPath, false, FieldOrigin.ARTIFICIAL_KEY);
        }

        List<PathSegment> pathSegments = path.segments();
        SelectionNode.Fragment inlineFragment = null;  // Track if we're inside an inline fragment
        List<String> fragmentPath = null;          // Path within the inline fragment
        // Track current type for implementing type checks. Without a start type, use the type the
        // plan declares at this position, so a field that only a concrete type has (e.g. a key or
        // requirement below an interface field) ends up in an inline fragment.
        String declaredTypeName = subgraph.equals(plan.subgraph) ? plan.declaredTypeAt(parentPath, graph) : null;
        String currentTypeName = startTypeName != null ? startTypeName : declaredTypeName;

        // The entity is a concrete type below an abstract field (e.g. Product below node: Node):
        // its fields go into an inline fragment on that type.
        if (startTypeName != null && declaredTypeName != null && !startTypeName.equals(declaredTypeName)
            && (graph.getInterfacesForType(startTypeName).contains(declaredTypeName)
                || graph.getUnionsForType(startTypeName).contains(declaredTypeName))
            && !path.segments().isEmpty()
            && graph.fieldEdgesFrom(new Node(declaredTypeName, subgraph))
                .noneMatch(edge -> edge.fieldName().equals(path.segments().get(0).fieldName()))) {
            plan.addArtificialField(IntrospectionFields.TYPENAME, currentPath, false, FieldOrigin.ARTIFICIAL_KEY);
            SelectionNode.Fragment fragment = plan.getOrCreateInlineFragment(currentPath, startTypeName, List.of());
            addPathInFragment(plan, fragment, currentPath, startTypeName, path.segments(), origin,
                fieldToResponseKey);
            return fieldToResponseKey;
        }

        for (int i = 0; i < pathSegments.size(); i++) {
            PathSegment segment = pathSegments.get(i);
            String fieldName = segment.fieldName();
            boolean hasChildren = (i < pathSegments.size() - 1); // intermediate nodes have children

            String parentTypeBefore = currentTypeName;
            // Check if field exists on the current type or needs inline fragments
            boolean needsInlineFragments = false;
            Set<String> implementingTypesWithField = new HashSet<>();

            if (currentTypeName != null && inlineFragment == null) {
                Node currentNode = new Node(currentTypeName, subgraph);
                var matchingEdge = graph.fieldEdgesFrom(currentNode)
                    .filter(edge -> edge.fieldName().equals(fieldName))
                    .findFirst();

                if (matchingEdge.isEmpty()) {
                    // Field not on current type - find the most specific types that have it
                    // Priority: interfaces first (to avoid duplication), then concrete types
                    Set<String> implTypes = new HashSet<>(graph.getImplementingTypesForInterface(currentTypeName));
                    implTypes.addAll(graph.getUnionMembers(currentTypeName));
                    Set<String> interfacesWithField = new HashSet<>();
                    Set<String> concreteTypesWithField = new HashSet<>();

                    for (String implType : implTypes) {
                        Node implNode = new Node(implType, subgraph);
                        boolean hasField = graph.fieldEdgesFrom(implNode)
                            .anyMatch(e -> e.fieldName().equals(fieldName));
                        if (hasField) {
                            // Check if this type is an interface (has implementing types)
                            if (!graph.getImplementingTypesForInterface(implType).isEmpty()) {
                                interfacesWithField.add(implType);
                            } else {
                                concreteTypesWithField.add(implType);
                            }
                        }
                    }

                    // Use interfaces if available (they're more general)
                    // But only use concrete types that aren't covered by an interface
                    for (String iface : interfacesWithField) {
                        implementingTypesWithField.add(iface);
                        // Remove concrete types that implement this interface
                        Set<String> ifaceImpls = graph.getImplementingTypesForInterface(iface);
                        concreteTypesWithField.removeAll(ifaceImpls);
                    }
                    // Add remaining concrete types not covered by interfaces
                    implementingTypesWithField.addAll(concreteTypesWithField);

                    needsInlineFragments = !implementingTypesWithField.isEmpty();
                    if (!needsInlineFragments) {
                        // Not on this type nor its implementations (e.g. __typename): stop type checks
                        currentTypeName = null;
                    }
                } else {
                    // Update current type to the field's target type for next iteration
                    currentTypeName = matchingEdge.get().target().typeName();
                }
            }

            if (needsInlineFragments) {
                // Field exists on implementing types but not on the interface
                // Add __typename for runtime type checking
                plan.addArtificialField(IntrospectionFields.TYPENAME, currentPath, false, FieldOrigin.ARTIFICIAL_KEY);

                // Create inline fragments for each implementing type that has the field
                for (String implType : implementingTypesWithField) {
                    SelectionNode.Fragment fragment = plan.getOrCreateInlineFragment(currentPath, implType, List.of());
                    // The current field and any remaining fields in the path
                    addPathInFragment(plan, fragment, currentPath, implType,
                        pathSegments.subList(i, pathSegments.size()), origin, fieldToResponseKey);
                }

                // All remaining fields have been added inside inline fragments, we're done
                return fieldToResponseKey;
            }

            if (inlineFragment != null) {
                // Add field inside inline fragment (its type is known for the fragment's own level only)
                String responseKey = addFieldInFragment(plan, inlineFragment, fragmentPath, currentPath,
                    fragmentPath.isEmpty() ? currentTypeName : null, segment, hasChildren, origin);
                fieldToResponseKey.put(ResponseKeys.segmentKey(segment), responseKey);
                fragmentPath.add(responseKey);
            } else {
                // Add field normally to the plan: with an alias if the client selects the response key with
                // other arguments or a field that cannot be merged with it
                boolean clientUsesOtherArguments = clientFields.getOrDefault(currentPath, Map.of())
                    .getOrDefault(fieldName, List.of()).stream()
                    .anyMatch(client -> !client.printedArguments()
                        .equals(SelectionNode.printedArguments(segment.arguments())));
                boolean alias = clientUsesOtherArguments || (subgraph.equals(plan.subgraph)
                    && clashes(plan, currentPath, parentTypeBefore, fieldName, segment.arguments()));
                String responseKey = plan.addArtificialField(fieldName, segment.arguments(), currentPath,
                    hasChildren, origin, alias);
                fieldToResponseKey.put(ResponseKeys.segmentKey(segment), responseKey);
                currentPath.add(responseKey);
            }

            // If this segment has an infix type condition (e.g., relatedMedia<Movie>.imdbCode),
            // add __typename and create an inline fragment for subsequent fields.
            // The inline fragment ensures type-specific fields are fetched correctly
            // when the parent field returns an interface or union type.
            if (segment.hasTypeCondition()) {
                if (inlineFragment != null) {
                    // Already inside an inline fragment, add __typename there
                    inlineFragment.addFieldAtPath(null, IntrospectionFields.TYPENAME, fragmentPath, false,
                        List.of(), List.of(), FieldOrigin.ARTIFICIAL_KEY);
                } else {
                    // Add __typename at current path for runtime type checking
                    plan.addArtificialField(IntrospectionFields.TYPENAME, currentPath, false, FieldOrigin.ARTIFICIAL_KEY);
                }

                // If there are more segments after this one, create inline fragment
                // for subsequent fields to be fetched inside the type condition
                if (i < pathSegments.size() - 1) {
                    inlineFragment = plan.getOrCreateInlineFragment(currentPath, segment.typeCondition(), List.of());
                    fragmentPath = new ArrayList<>();
                }
                // Update current type to the type condition
                currentTypeName = segment.typeCondition();
            }
        }

        return fieldToResponseKey;
    }

    /**
     * Selects path segments in a fragment on {@code typeName} at a response path, recording their response
     * keys; each segment's parent type follows the subgraph's fields from the fragment's type.
     */
    private void addPathInFragment(SubgraphPlan plan, SelectionNode.Fragment fragment, List<String> responsePath,
                                   String typeName, List<PathSegment> segments, FieldOrigin origin,
                                   Map<String, String> fieldToResponseKey) {
        List<String> fragPath = new ArrayList<>();
        String parentType = typeName;
        for (int j = 0; j < segments.size(); j++) {
            PathSegment segment = segments.get(j);
            boolean hasChildren = j < segments.size() - 1;
            String responseKey = addFieldInFragment(plan, fragment, fragPath, responsePath, parentType, segment,
                hasChildren, origin);
            fieldToResponseKey.put(ResponseKeys.segmentKey(segment), responseKey);
            fragPath.add(responseKey);
            String fieldParent = parentType;
            parentType = parentType == null ? null : graph.findFieldEdge(new Node(fieldParent, plan.subgraph),
                segment.fieldName()).map(edge -> edge.target().typeName()).orElse(null);
        }
    }

    /**
     * Adds a field the planner needs at a path of response keys within a fragment (the fragment being at
     * {@code responsePath}), on an object of {@code parentType} (null if unknown): with an alias where another
     * field with its response key there cannot be merged with it.
     *
     * @return the field's response key
     */
    private String addFieldInFragment(SubgraphPlan plan, SelectionNode.Fragment fragment, List<String> fragPath,
                                      List<String> responsePath, String parentType, PathSegment segment,
                                      boolean hasChildren, FieldOrigin origin) {
        SelectionNode container = fragment.root.descend(fragPath, true, false);
        if (container == null) {
            throw new PlanningException("Parent node not found in path " + fragPath + " while adding field '"
                + segment.fieldName() + "'");
        }
        List<String> fieldPath = new ArrayList<>(responsePath);
        fieldPath.addAll(fragPath);
        SelectionNode sameKey = container.children.get(segment.fieldName());
        boolean conditional = sameKey != null && !sameKey.queryDirectives.isEmpty();
        if (conditional
            || parentType != null && clashes(plan, fieldPath, parentType, segment.fieldName(), segment.arguments())) {
            // Not into a field the client selects with @skip/@include, which may be missing
            return container.addArtificialField(segment.fieldName(), segment.arguments(), hasChildren, origin, true);
        }
        container.addField(null, segment.fieldName(), hasChildren, segment.arguments(), List.of(), origin);
        return segment.fieldName();
    }

    /**
     * Selects a lookup key path in the fragment a client field is selected in, at the fragment's current
     * position.
     */
    Map<String, String> addKeyPathInFragment(InlineFragmentContext fragment, Path keyPath) {
        Map<String, String> responseKeys = new LinkedHashMap<>();
        List<String> fragPath = new ArrayList<>(fragment.currentPath);
        String parentType = fragment.currentPath.isEmpty() ? fragment.typeName : null;
        List<PathSegment> segments = keyPath.segments();
        for (int i = 0; i < segments.size(); i++) {
            PathSegment segment = segments.get(i);
            String responseKey = addFieldInFragment(fragment.plan, fragment.fragmentNode, fragPath,
                fragment.basePath, parentType, segment, i < segments.size() - 1, FieldOrigin.ARTIFICIAL_KEY);
            responseKeys.put(segment.fieldName(), responseKey);
            fragPath.add(responseKey);
            parentType = null;
        }
        return responseKeys;
    }

    /**
     * Whether a field the planner adds at a response path, on an object of {@code parentType} (null: the type
     * declared there), cannot be merged with another field of the same response key there (GraphQL's
     * FieldsInSetCanMerge): one the plan already selects there, in any fragment, or one the client selects there. On the same or an abstract parent type
     * the fields must be the same field with the same arguments; on two object types they must have the same
     * output type.
     */
    private boolean clashes(SubgraphPlan plan, List<String> responsePath, String parentType, String fieldName,
                            List<Argument> arguments) {
        List<ClientField> clientSelections = clientFields.getOrDefault(responsePath, Map.of())
            .getOrDefault(fieldName, List.of());
        SelectionNode node = plan.root.descend(plan.adjustPath(responsePath), false, false);
        if (clientSelections.isEmpty() && (node == null || !selectsResponseKey(node, fieldName))) {
            return false;
        }
        String declaredType = plan.declaredTypeAt(responsePath, graph);
        if (parentType == null) {
            parentType = declaredType;
            if (parentType == null) {
                return false;
            }
        }
        List<String> printedArguments = SelectionNode.printedArguments(arguments);
        for (ClientField client : clientSelections) {
            String clientParent = client.typeCondition() != null ? client.typeCondition() : declaredType;
            if (clientParent != null && !mergeable(plan.subgraph, parentType, fieldName, printedArguments,
                    clientParent, client.fieldName(), client.printedArguments())) {
                return true;
            }
        }
        return node != null && selectedClashes(plan.subgraph, node, declaredType, parentType, fieldName,
            printedArguments);
    }

    /** Whether the node or one of its inline fragments selects a field with this response key. */
    private static boolean selectsResponseKey(SelectionNode node, String responseKey) {
        return node.children.containsKey(responseKey)
            || node.inlineFragments.stream().anyMatch(fragment -> selectsResponseKey(fragment.root, responseKey));
    }

    private boolean selectedClashes(String subgraph, SelectionNode node, String nodeType, String parentType,
                                    String fieldName, List<String> printedArguments) {
        SelectionNode selected = node.children.get(fieldName);
        if (selected != null && nodeType != null && !mergeable(subgraph, parentType, fieldName, printedArguments,
                nodeType, selected.fieldName, SelectionNode.printedArguments(selected.queryArguments))) {
            return true;
        }
        for (SelectionNode.Fragment fragment : node.inlineFragments) {
            if (selectedClashes(subgraph, fragment.root,
                    fragment.typeCondition != null ? fragment.typeCondition : nodeType, parentType, fieldName,
                    printedArguments)) {
                return true;
            }
        }
        return false;
    }

    private boolean mergeable(String subgraph, String parentType, String fieldName, List<String> arguments,
                              String otherParentType, String otherFieldName, List<String> otherArguments) {
        String type = graph.fieldType(parentType, subgraph, fieldName);
        String otherType = graph.fieldType(otherParentType, subgraph, otherFieldName);
        if (otherType == null) {
            // Not a field of this subgraph: it is selected in another operation
            return true;
        }
        boolean sameField = fieldName.equals(otherFieldName) && arguments.equals(otherArguments);
        if (parentType.equals(otherParentType) || graph.isAbstract(parentType) || graph.isAbstract(otherParentType)) {
            return sameField && Objects.equals(type, otherType);
        }
        return Objects.equals(type, otherType);
    }
}
