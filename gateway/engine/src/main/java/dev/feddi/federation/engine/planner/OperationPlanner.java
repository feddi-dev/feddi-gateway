package dev.feddi.federation.engine.planner;

import dev.feddi.federation.engine.Constants;

import dev.feddi.federation.engine.IntrospectionFields;
import dev.feddi.federation.engine.graph.Edge;
import dev.feddi.federation.engine.graph.Graph;
import dev.feddi.federation.engine.graph.LookupMoveEdge;
import dev.feddi.federation.engine.graph.Node;
import dev.feddi.federation.engine.query.FieldSelection;
import dev.feddi.federation.engine.query.InlineFragmentSelection;
import dev.feddi.federation.engine.query.Operation;
import dev.feddi.federation.engine.query.Selection;
import graphql.language.Directive;
import graphql.language.OperationDefinition;
import graphql.language.Type;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Main query planner that orchestrates the planning process.
 * Takes a Graph and Operation, produces an ExecutionPlan.
 */
public final class OperationPlanner {
    
    private final Graph graph;
    private final PathFinder pathFinder;
    
    public OperationPlanner(Graph graph) {
        this.graph = graph;
        this.pathFinder = new PathFinder(graph);
    }
    
    /**
     * Plans the execution of a query.
     *
     * @param query the query to plan
     * @return the execution plan
     */
    public ExecutionPlan plan(Operation query) {
        // Find the root node for the query's operation type (Query or Mutation)
        Node rootNode = graph.getRootNode(query.rootType());
        if (rootNode == null) {
            throw new IllegalArgumentException(
                "No root node found for operation type '" + query.rootType() + "'"
            );
        }

        // Start planning from the root
        OperationPath rootPath = OperationPath.startAt(rootNode);

        // Determine the operation type for subgraph operations
        OperationDefinition.Operation operationType = Constants.MUTATION.equals(query.rootType())
            ? OperationDefinition.Operation.MUTATION
            : OperationDefinition.Operation.QUERY;

        // Collect all planning results, with query variable definitions for pass-through
        PlanningContext context = new PlanningContext(query.variableDefinitions(), operationType, graph);
        collectClientFields(query.selections(), List.of(), null, context.clientFields);

        // Plan each top-level selection
        for (Selection selection : query.selections()) {
            planSelection(rootPath, selection, List.of(), context);
        }

        // Build the execution plan from the planning context
        return context.buildPlan();
    }
    
    /**
     * Records each field the client selects, by response path and response key (inline fragments add no path), with
     * the type condition it is selected under, so that a field the planner adds itself gets an alias where it
     * could not be merged with one of them.
     */
    private static void collectClientFields(List<Selection> selections, List<String> parentPath, String typeCondition,
                                            Map<List<String>, Map<String, List<FieldPathSelector.ClientField>>> clientFields) {
        for (Selection selection : selections) {
            if (selection instanceof FieldSelection field) {
                clientFields.computeIfAbsent(parentPath, k -> new HashMap<>())
                    .computeIfAbsent(field.responseKey(), k -> new ArrayList<>())
                    .add(new FieldPathSelector.ClientField(typeCondition, field.fieldName(),
                        SelectionNode.printedArguments(field.arguments())));
                List<String> childPath = new ArrayList<>(parentPath);
                childPath.add(field.responseKey());
                collectClientFields(field.subSelections(), List.copyOf(childPath), null, clientFields);
            } else if (selection instanceof InlineFragmentSelection fragment) {
                collectClientFields(fragment.subSelections(), parentPath,
                    fragment.typeCondition() != null ? fragment.typeCondition() : typeCondition, clientFields);
            }
        }
    }

    /**
     * Plans a single selection (field or inline fragment) recursively.
     *
     * @param currentPath the current path through the graph
     * @param selection the selection to plan
     * @param parentPath the path of parent response keys leading to this selection (for hierarchy tracking)
     * @param context the planning context
     */
    private void planSelection(OperationPath currentPath, Selection selection,
                               List<String> parentPath, PlanningContext context) {
        planSelection(currentPath, selection, parentPath, context, null);
    }

    /**
     * Plans a single selection with optional inline fragment context.
     * When fragmentContext is provided, fields are added to that fragment's selection tree.
     */
    private void planSelection(OperationPath currentPath, Selection selection,
                               List<String> parentPath, PlanningContext context,
                               InlineFragmentContext fragmentContext) {
        if (selection instanceof FieldSelection fieldSelection) {
            planFieldSelection(currentPath, fieldSelection, parentPath, context, fragmentContext, null);
        } else if (selection instanceof InlineFragmentSelection inlineFragment) {
            // Nested inline fragments start fresh - they're handled separately
            planInlineFragment(currentPath, inlineFragment, parentPath, context);
        }
    }

    /**
     * The client's inline fragment a field is selected in directly ({@code ... on Book @include(if: $b) { title }}):
     * the field goes into such a fragment in the plan that selects it.
     */
    private record FragmentScope(String typeCondition, List<Directive> directives) {
    }

    /**
     * Plans a field selection. The field goes into {@code fragmentContext} (the fragment an enclosing field was
     * selected in) or, for a field directly in a client fragment ({@code scope}), into that fragment in the plan
     * that selects the field; otherwise into its plan's selections. A field resolved in another plan (through a
     * lookup) leaves the fragment context; the lookup's keys are selected in the fragment.
     */
    private void planFieldSelection(OperationPath currentPath, FieldSelection selection,
                                    List<String> parentPath, PlanningContext context,
                                    InlineFragmentContext fragmentContext, FragmentScope scope) {
        String fieldName = selection.fieldName();

        // Find paths to resolve this field
        List<OperationPath> paths = pathFinder.findPaths(currentPath, fieldName);

        if (paths.isEmpty()) {
            if (planPerPossibleType(currentPath, selection, parentPath, context)) {
                return;
            }
            throw new PlanningException("Cannot find path to resolve field: " + fieldName);
        }

        // Use the best path (lowest cost)
        OperationPath bestPath = paths.get(0);

        // Introspection fields are handled opaquely - store the entire selection tree
        if ("$introspection".equals(bestPath.currentSubgraph())) {
            context.recordOpaqueFieldResolution(bestPath, selection, parentPath);
            return;
        }

        boolean resolvedElsewhere = addsLookup(currentPath, bestPath)
            || !currentPath.currentSubgraph().equals(bestPath.currentSubgraph());
        InlineFragmentContext target = fragmentContext;
        if (scope != null) {
            target = resolvedElsewhere
                ? context.getOrCreateInlineFragmentForLookup(bestPath, parentPath, scope.typeCondition(),
                    scope.directives())
                : context.inlineFragmentIn(context.planFor(currentPath, parentPath), parentPath, scope.typeCondition(),
                    scope.directives());
        }
        if (target != null && scope == null && resolvedElsewhere) {
            context.recordFieldResolution(bestPath, selection.alias(), fieldName, parentPath,
                selection.hasSubSelections(), selection.arguments(), selection.directives(), target);
            target = null;
        } else if (target != null) {
            target.addField(selection.alias(), fieldName, selection.hasSubSelections(),
                selection.arguments(), selection.directives());
            context.recordFieldResolutionForFragment(bestPath, fieldName, parentPath);
        } else {
            context.recordFieldResolution(bestPath, selection.alias(), fieldName, parentPath,
                selection.hasSubSelections(), selection.arguments(), selection.directives());
        }

        // Recursively plan sub-selections, passing the fragment context to keep fields inside the fragment
        if (selection.hasSubSelections()) {
            List<String> childParentPath = new ArrayList<>(parentPath);
            childParentPath.add(selection.responseKey());

            if (target != null) {
                target.enterField(selection.responseKey());
            }

            Set<OperationPath> recordedPaths = new HashSet<>(List.of(bestPath));
            for (Selection subSelection : selection.subSelections()) {
                OperationPath parentForSub = bestPath;
                if (target == null && paths.size() > 1 && !resolvable(bestPath, subSelection)) {
                    // Sibling-aware choice: a child the best path cannot resolve may be resolvable when this field
                    // is also selected in another subgraph (e.g. a shared root field whose schemas each have
                    // different fields of its type).
                    for (OperationPath alternative : paths.subList(1, paths.size())) {
                        if (resolvable(alternative, subSelection)) {
                            parentForSub = alternative;
                            if (recordedPaths.add(alternative)) {
                                context.recordFieldResolution(alternative, selection.alias(), fieldName, parentPath,
                                    true, selection.arguments(), selection.directives());
                            }
                            break;
                        }
                    }
                }
                planSelection(parentForSub, subSelection, childParentPath, context, target);
            }

            if (target != null) {
                target.exitField();
            }
        }
    }

    /**
     * Whether a selection can be planned from {@code parent}: its field has a path, and each of its children can
     * be resolved from one of the field's paths (not necessarily the same one). Inline fragments count as
     * resolvable; they are planned with their own fallbacks.
     */
    private boolean resolvable(OperationPath parent, Selection selection) {
        if (!(selection instanceof FieldSelection field)) {
            return true;
        }
        List<OperationPath> fieldPaths = pathFinder.findPaths(parent, field.fieldName());
        if (fieldPaths.isEmpty()) {
            return false;
        }
        for (Selection child : field.subSelections()) {
            if (fieldPaths.stream().noneMatch(path -> resolvable(path, child))) {
                return false;
            }
        }
        return true;
    }

    /**
     * A field of an interface or union that cannot be resolved on the abstract type as a whole (e.g. one
     * implementation's field was moved to another subgraph with @override) is selected per possible type:
     * {@code createdAt} becomes {@code ... on ImagePost { createdAt } ... on TextPost { createdAt }}, each resolved
     * where that type has it. Only the types the current subgraph can return are considered.
     *
     * @return false if the current type is not abstract or the field cannot be resolved for its types either
     */
    private boolean planPerPossibleType(OperationPath currentPath, FieldSelection selection, List<String> parentPath,
                                        PlanningContext context) {
        String typeName = currentPath.typeContext() != null ? currentPath.typeContext() : currentPath.tail().typeName();
        String subgraph = currentPath.currentSubgraph();
        Set<String> possibleTypes = new TreeSet<>(graph.getImplementingTypesForInterface(typeName));
        possibleTypes.addAll(graph.getUnionMembers(typeName));
        possibleTypes.removeIf(type -> !graph.containsNode(new Node(type, subgraph))
            || !graph.getImplementingTypesForInterface(type).isEmpty());
        if (possibleTypes.isEmpty() || possibleTypes.stream()
                .anyMatch(type -> pathFinder.findPaths(currentPath.withTypeContext(type), selection.fieldName()).isEmpty())) {
            return false;
        }
        for (String type : possibleTypes) {
            planInlineFragment(currentPath, new InlineFragmentSelection(type, List.of(), List.of(selection)),
                parentPath, context);
        }
        return true;
    }

    /**
     * Plans an inline fragment by planning its selections with the type context.
     * The type condition affects which fields can be resolved (polymorphic selection).
     */
    private void planInlineFragment(OperationPath currentPath, InlineFragmentSelection fragment,
                                    List<String> parentPath, PlanningContext context) {
        String typeCondition = fragment.typeCondition();

        // Check if the type condition matches the current node's type
        // If so, the inline fragment is redundant and we can just plan fields directly
        // e.g., "... on Content { id }" when we're at Content is equivalent to just "{ id }"
        String currentTypeName = currentPath.tail().typeName();
        boolean typeConditionMatchesCurrentType = typeCondition != null && typeCondition.equals(currentTypeName);

        if (typeConditionMatchesCurrentType) {
            // Type condition matches current type - plan fields directly without inline fragment
            for (Selection subSelection : fragment.subSelections()) {
                planSelection(currentPath, subSelection, parentPath, context);
            }
            return;
        }

        // When we have an inline fragment with a type condition that differs from the
        // current type (e.g., "... on Book" when at Media union), we need __typename
        // at the parent level to determine which inline fragment to apply at runtime.
        if (typeCondition != null) {
            context.planFor(currentPath, parentPath).addField(IntrospectionFields.TYPENAME, parentPath, false,
                List.of(), List.of(), FieldOrigin.ARTIFICIAL_KEY);
        }

        // Narrow the path to the concrete type so field lookups use the correct node
        // e.g., when at Content/content and entering "... on Article", use Article for field lookup
        OperationPath narrowedPath = typeCondition != null
            ? currentPath.withTypeContext(typeCondition)
            : currentPath;

        // Don't eagerly create inline fragment context - it will be created on demand
        // when we find fields that resolve in the same subgraph. This avoids creating
        // empty inline fragments when all fields go to a different subgraph.

        // Plan each selection within the fragment; lookups below it only apply to objects of its type
        String outerTypeCondition = typeCondition != null
            ? context.enterTypeCondition(parentPath.size(), typeCondition) : null;
        for (Selection subSelection : fragment.subSelections()) {
            if (subSelection instanceof FieldSelection fieldSelection) {
                planFieldSelection(narrowedPath, fieldSelection, parentPath, context, null,
                    new FragmentScope(typeCondition, fragment.directives()));
            } else if (subSelection instanceof InlineFragmentSelection nestedFragment) {
                // Nested inline fragments are rare but possible
                planInlineFragment(narrowedPath, nestedFragment, parentPath, context);
            }
        }
        if (typeCondition != null) {
            context.exitTypeCondition(parentPath.size(), outerTypeCondition);
        }
    }

    /**
     * Whether {@code next} (a path extending {@code current}) goes through a lookup: the field is then resolved in
     * another step, even if it ends in the same subgraph (re-entering it, e.g. for a field's @require values).
     */
    private static boolean addsLookup(OperationPath current, OperationPath next) {
        List<Edge> edges = next.getEdges();
        for (int i = current.getEdges().size(); i < edges.size(); i++) {
            if (edges.get(i) instanceof LookupMoveEdge) {
                return true;
            }
        }
        return false;
    }
}
