package dev.feddi.federation.engine.planner;

import dev.feddi.federation.engine.Constants;
import dev.feddi.federation.engine.graph.Graph;
import dev.feddi.federation.engine.graph.LookupArgument;
import dev.feddi.federation.engine.graph.LookupMoveEdge;
import dev.feddi.federation.engine.graph.Node;
import dev.feddi.federation.engine.parser.FieldSelectionMap.SelectedValue;
import dev.feddi.federation.engine.query.FieldSelection;
import dev.feddi.federation.engine.query.InlineFragmentSelection;
import dev.feddi.federation.engine.query.Selection;
import graphql.language.Argument;
import graphql.language.Directive;
import graphql.language.Field;
import graphql.language.InlineFragment;
import graphql.language.OperationDefinition;
import graphql.language.SelectionSet;
import graphql.language.Type;
import graphql.language.TypeName;
import graphql.language.VariableDefinition;
import graphql.language.VariableReference;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One subgraph operation of a plan being built: its selection tree, the lookup that enters it (null for a root
 * operation) and the requirements its variables are filled from.
 *
 * <p>Response paths passed in are the client's (from the operation root); a lookup plan strips its entry path.
 */
final class SubgraphPlan {
    final int id;
    final String subgraph;
    final Map<String, RequirementInfo> requirements = new LinkedHashMap<>();

    // Lookup origin tracking (for dependent subgraphs entered via @lookup)
    Map<Integer, String> lookupPathTypes = Map.of(); // type conditions along the lookup's entry path
    LookupMoveEdge lookupEdge;                  // the lookup that enters this plan, null for root plans
    List<String> lookupEntryPath;               // The parentPath when we entered via lookup

    // Arguments from @require directives, by the path of field names to the field
    final Map<List<String>, List<FieldArgumentInfo>> fieldArguments = new HashMap<>();

    // Query-level variable definitions for pass-through
    private final List<VariableDefinition> queryVariableDefinitions;

    // The operation type (QUERY or MUTATION) for this plan
    private final OperationDefinition.Operation operationType;

    final SelectionNode root = SelectionNode.root();

    /**
     * Info about a field argument from @require.
     */
    record FieldArgumentInfo(String argumentName, String variableName) {}

    /**
     * Holds requirement information including the selection and type.
     */
    record RequirementInfo(SelectedValue selection, Type<?> type) {}

    SubgraphPlan(int id, String subgraph, List<VariableDefinition> queryVariableDefinitions,
                 OperationDefinition.Operation operationType) {
        this.id = id;
        this.subgraph = subgraph;
        this.queryVariableDefinitions = queryVariableDefinitions != null ? queryVariableDefinitions : List.of();
        this.operationType = operationType;
    }

    /** The entity type the plan's lookup resolves (e.g. "Book"), or null for a root plan. */
    String lookupTargetType() {
        return lookupEdge == null ? null : lookupEdge.target().typeName();
    }

    /**
     * Sets the lookup origin information for a subgraph entered via a @lookup edge.
     */
    void setLookupOrigin(LookupMoveEdge lookupEdge, List<String> entryPath, Map<Integer, String> pathTypes) {
        this.lookupEdge = lookupEdge;
        this.lookupPathTypes = pathTypes;
        this.lookupEntryPath = new ArrayList<>(entryPath);
    }

    /**
     * A response path relative to this plan's selections: without the lookup entry path if it starts with it.
     */
    List<String> adjustPath(List<String> parentPath) {
        if (lookupEntryPath != null && parentPath.size() >= lookupEntryPath.size()
            && parentPath.subList(0, lookupEntryPath.size()).equals(lookupEntryPath)) {
            return parentPath.subList(lookupEntryPath.size(), parentPath.size());
        }
        return parentPath;
    }

    /** The selections at a response path, created if missing. */
    private SelectionNode nodeAt(List<String> parentPath, boolean matchFieldNames) {
        return root.descend(adjustPath(parentPath), matchFieldNames, true);
    }

    /**
     * Adds an artificial field (for @key or @require) at the specified position.
     * Reuses existing field if already present, or generates unique alias if there's a clash.
     *
     * @return the response key where the value will be found (may be aliased)
     */
    String addArtificialField(String fieldName, List<String> parentPath, boolean hasChildren, FieldOrigin origin) {
        return addArtificialField(fieldName, List.of(), parentPath, hasChildren, origin, false);
    }

    /**
     * Adds an artificial field selected with {@code arguments} (see {@link SelectionNode#addArtificialField}).
     */
    String addArtificialField(String fieldName, List<Argument> arguments, List<String> parentPath,
                              boolean hasChildren, FieldOrigin origin, boolean alias) {
        return nodeAt(parentPath, false).addArtificialField(fieldName, arguments, hasChildren, origin, alias);
    }

    /** Whether this plan's selection selects the object at {@code parentPath} (a response path). */
    boolean selectsPath(List<String> parentPath) {
        return root.descend(adjustPath(parentPath), false, false) != null;
    }

    /**
     * The type this plan declares at a response path: from the plan's root type (the lookup's
     * return type, or the operation's root type) along the selection tree and the subgraph's
     * fields. Null if a field on the way is not known.
     */
    String declaredTypeAt(List<String> parentPath, Graph graph) {
        String type = lookupEdge != null ? lookupTargetType()
            : operationType == OperationDefinition.Operation.MUTATION ? Constants.MUTATION : Constants.QUERY;
        SelectionNode node = root;
        for (String responseKey : adjustPath(parentPath)) {
            SelectionNode child = node == null ? null : node.children.get(responseKey);
            String fieldName = child != null ? child.fieldName : responseKey;
            String current = type;
            var edge = graph.fieldEdgesFrom(new Node(current, subgraph))
                .filter(e -> e.fieldName().equals(fieldName))
                .findFirst();
            if (edge.isEmpty()) {
                return null;
            }
            type = edge.get().target().typeName();
            node = child;
        }
        return type;
    }

    /**
     * Adds a field the client requested, with its alias, arguments and directives.
     */
    void addFieldWithAlias(String alias, String fieldName, List<String> parentPath, boolean hasChildren,
                           List<Argument> queryArguments, List<Directive> queryDirectives) {
        addField(alias, fieldName, parentPath, hasChildren, queryArguments, queryDirectives, FieldOrigin.REQUESTED);
    }

    /**
     * Adds a field at the specified hierarchical position with origin tracking.
     */
    void addField(String fieldName, List<String> parentPath, boolean hasChildren,
                  List<Argument> queryArguments, List<Directive> queryDirectives, FieldOrigin origin) {
        addField(null, fieldName, parentPath, hasChildren, queryArguments, queryDirectives, origin);
    }

    /**
     * Adds a field at the specified hierarchical position with alias and origin tracking.
     */
    void addField(String alias, String fieldName, List<String> parentPath, boolean hasChildren,
                  List<Argument> queryArguments, List<Directive> queryDirectives, FieldOrigin origin) {
        nodeAt(parentPath, false).addField(alias, fieldName, hasChildren, queryArguments, queryDirectives, origin);
    }

    void addRequirement(String name, SelectedValue selection, Type<?> type) {
        requirements.putIfAbsent(name, new RequirementInfo(selection, type));
    }

    /**
     * Adds the value of a @require argument as a variable named after the argument, or numbered when another
     * requirement with a different value already has that name (two fields' "price" arguments requiring
     * price(withDiscount: true) and price(withDiscount: false)). Returns the variable name.
     */
    String addRequirementVariable(String argumentName, SelectedValue selection, Type<?> type) {
        String name = argumentName;
        for (int n = 2; requirements.containsKey(name) && !requirements.get(name).selection().equals(selection); n++) {
            name = argumentName + "_" + n;
        }
        addRequirement(name, selection, type);
        return name;
    }

    /**
     * Collects all artificial field paths from the selection tree.
     * Returns dot-notation paths like "orders.customerId" for fields that were
     * added for internal purposes (not requested by the client).
     * Paths are prefixed with lookupEntryPath if this is a lookup step.
     */
    Set<String> collectArtificialFieldPaths() {
        Set<String> paths = new HashSet<>();
        collectFieldPathsRecursive(root, lookupPrefix(), null, paths, false);
        return paths;
    }

    /**
     * Collects all requested field paths from the selection tree.
     * Returns dot-notation paths for fields that were explicitly requested by the client.
     * Paths are prefixed with lookupEntryPath if this is a lookup step.
     */
    Set<String> collectRequestedFieldPaths() {
        Set<String> paths = new HashSet<>();
        collectFieldPathsRecursive(root, lookupPrefix(), null, paths, true);
        return paths;
    }

    /**
     * The response path of this plan's selections: for a lookup step, fields are stored without the lookup entry
     * path, which collected paths add back to match the response structure.
     */
    private String lookupPrefix() {
        if (lookupEntryPath == null || lookupEntryPath.isEmpty()) {
            return "";
        }
        return String.join(".", lookupEntryPath);
    }

    /**
     * Collects "parent.field" paths, or "parent<Type>.field" for a field inside a fragment on Type: the same
     * response key can be internal for one type and requested for another (... on E1 { code } next to a key
     * code selected for E2's lookup).
     */
    private void collectFieldPathsRecursive(SelectionNode node, String currentPath, String typeCondition,
                                            Set<String> paths, boolean collectRequested) {
        String parent = typeCondition == null ? currentPath : currentPath + "<" + typeCondition + ">";
        for (SelectionNode child : node.children.values()) {
            // Use responseKey (alias if present) since that's what appears in the response
            String responseKey = child.responseKey();
            String fieldPath = parent.isEmpty() ? responseKey : parent + "." + responseKey;
            if (collectRequested == (child.origin == FieldOrigin.REQUESTED)) {
                paths.add(fieldPath);
            }
            String childPath = currentPath.isEmpty() ? responseKey : currentPath + "." + responseKey;
            collectFieldPathsRecursive(child, childPath, null, paths, collectRequested);
        }
        for (SelectionNode.Fragment fragment : node.inlineFragments) {
            collectFieldPathsRecursive(fragment.root, currentPath,
                fragment.typeCondition != null ? fragment.typeCondition : typeCondition, paths, collectRequested);
        }
    }

    /**
     * Adds an opaque field that should be rendered with its full selection tree.
     * Used for introspection fields (__schema, __type) where the entire selection
     * is passed to graphql-java for execution without further planning.
     */
    void addOpaqueField(FieldSelection selection, List<String> parentPath) {
        SelectionNode fieldNode = nodeAt(parentPath, true).children.computeIfAbsent(selection.fieldName(),
            name -> new SelectionNode(name, selection.hasSubSelections()));
        fieldNode.opaqueSelection = selection;
        fieldNode.queryArguments = selection.arguments();
        fieldNode.setQueryDirectives(selection.directives());
    }

    /** Whether the field at a path of field names already has this @require argument. */
    boolean hasFieldArgument(List<String> fieldPath, String argumentName) {
        return fieldArguments.getOrDefault(fieldPath, List.of()).stream()
            .anyMatch(argument -> argument.argumentName().equals(argumentName));
    }

    /**
     * Adds an argument that should be passed to a field when building the operation.
     */
    void addFieldArgument(List<String> fieldPath, String argumentName, String variableName) {
        List<FieldArgumentInfo> args = fieldArguments.computeIfAbsent(new ArrayList<>(fieldPath), k -> new ArrayList<>());
        // Deduplicate: don't add if same argument already exists
        for (FieldArgumentInfo existing : args) {
            if (existing.argumentName().equals(argumentName)) {
                return;
            }
        }
        args.add(new FieldArgumentInfo(argumentName, variableName));
    }

    /**
     * Gets or creates an inline fragment at the specified position.
     */
    SelectionNode.Fragment getOrCreateInlineFragment(List<String> parentPath, String typeCondition,
                                                     List<Directive> directives) {
        return nodeAt(parentPath, true).getOrCreateInlineFragment(typeCondition, directives);
    }

    /**
     * Builds an OperationDefinition from the collected field structure.
     * Includes variable definitions for each requirement.
     * If this subgraph was entered via lookup, wraps the selection set with the lookup field.
     */
    OperationDefinition buildOperation() {
        SelectionSet innerSelectionSet = buildSelectionSet(root, new ArrayList<>());
        SelectionSet finalSelectionSet;

        if (lookupEdge != null) {
            // Build lookup field with arguments
            List<Argument> args = new ArrayList<>();
            for (LookupArgument lookupArg : lookupEdge.lookupArguments()) {
                args.add(Argument.newArgument()
                    .name(lookupArg.argumentName())
                    .value(VariableReference.newVariableReference()
                        .name(lookupArg.argumentName())
                        .build())
                    .build());
            }

            if (!lookupTargetType().equals(lookupEdge.returnType())) {
                // The lookup returns an interface or union: select the entity type's fields
                innerSelectionSet = SelectionSet.newSelectionSet()
                    .selection(InlineFragment.newInlineFragment()
                        .typeCondition(TypeName.newTypeName(lookupTargetType()).build())
                        .selectionSet(innerSelectionSet)
                        .build())
                    .build();
            }
            Field lookupField = Field.newField()
                .name(lookupEdge.lookupField())
                .arguments(args)
                .selectionSet(innerSelectionSet)
                .build();

            finalSelectionSet = SelectionSet.newSelectionSet()
                .selections(List.of(lookupField))
                .build();
            // A nested lookup (Query.lookups.brandById) is selected through its argument-less parent fields
            List<String> lookupPath = lookupEdge.lookupPath();
            for (int i = lookupPath.size() - 1; i >= 0; i--) {
                finalSelectionSet = SelectionSet.newSelectionSet()
                    .selections(List.of(Field.newField(lookupPath.get(i)).selectionSet(finalSelectionSet).build()))
                    .build();
            }
        } else {
            finalSelectionSet = innerSelectionSet;
        }

        // Build variable definitions from requirements
        List<VariableDefinition> variableDefinitions = new ArrayList<>();
        Set<String> definedVarNames = new HashSet<>();

        for (var entry : requirements.entrySet()) {
            Type<?> varType = entry.getValue().type();
            if (varType != null) {
                VariableDefinition varDef = VariableDefinition.newVariableDefinition()
                    .name(entry.getKey())
                    .type(varType)
                    .build();
                variableDefinitions.add(varDef);
                definedVarNames.add(entry.getKey());
            }
        }

        // Add query variable definitions for variables used in field arguments
        Set<String> usedQueryVars = new HashSet<>();
        collectUsedQueryVariables(root, usedQueryVars);
        for (VariableDefinition queryVarDef : queryVariableDefinitions) {
            if (usedQueryVars.contains(queryVarDef.getName()) && !definedVarNames.contains(queryVarDef.getName())) {
                variableDefinitions.add(queryVarDef);
                definedVarNames.add(queryVarDef.getName());
            }
        }

        // Lookup steps are always queries, even if the original operation was a mutation
        // because @lookup fields are defined on Query type
        OperationDefinition.Operation effectiveOperationType =
            (lookupEdge != null) ? OperationDefinition.Operation.QUERY : operationType;

        var builder = OperationDefinition.newOperationDefinition()
            .operation(effectiveOperationType)
            .selectionSet(finalSelectionSet);

        if (!variableDefinitions.isEmpty()) {
            builder.variableDefinitions(variableDefinitions);
        }

        return builder.build();
    }

    /**
     * Collects all query variable names referenced in field arguments and directives.
     */
    private void collectUsedQueryVariables(SelectionNode node, Set<String> usedVars) {
        for (Argument arg : node.queryArguments) {
            collectVariableReferencesFromValue(arg.getValue(), usedVars);
        }
        // Directives can reference variables too (e.g., @skip(if: $var))
        for (Directive directive : node.queryDirectives) {
            for (Argument arg : directive.getArguments()) {
                collectVariableReferencesFromValue(arg.getValue(), usedVars);
            }
        }
        for (SelectionNode child : node.children.values()) {
            collectUsedQueryVariables(child, usedVars);
        }
        for (SelectionNode.Fragment fragment : node.inlineFragments) {
            for (Directive directive : fragment.directives) {
                for (Argument arg : directive.getArguments()) {
                    collectVariableReferencesFromValue(arg.getValue(), usedVars);
                }
            }
            collectUsedQueryVariables(fragment.root, usedVars);
        }
    }

    private void collectVariableReferencesFromValue(graphql.language.Value<?> value, Set<String> usedVars) {
        if (value instanceof VariableReference varRef) {
            usedVars.add(varRef.getName());
        } else if (value instanceof graphql.language.ArrayValue arrayValue) {
            for (graphql.language.Value<?> element : arrayValue.getValues()) {
                collectVariableReferencesFromValue(element, usedVars);
            }
        } else if (value instanceof graphql.language.ObjectValue objectValue) {
            for (graphql.language.ObjectField field : objectValue.getObjectFields()) {
                collectVariableReferencesFromValue(field.getValue(), usedVars);
            }
        }
    }

    /**
     * Recursively builds a SelectionSet from the tree structure, tracking the path of field names for @require
     * arguments.
     */
    private SelectionSet buildSelectionSet(SelectionNode node, List<String> currentPath) {
        if (node.children.isEmpty() && node.inlineFragments.isEmpty()) {
            return null;
        }

        List<graphql.language.Selection<?>> selections = new ArrayList<>();

        for (SelectionNode child : node.children.values()) {
            List<String> childPath = new ArrayList<>(currentPath);
            childPath.add(child.fieldName);

            Field.Builder fieldBuilder = Field.newField()
                .name(child.fieldName)
                .alias(child.alias);

            // The client's arguments, then the @require arguments
            List<Argument> allArguments = new ArrayList<>(child.queryArguments);
            for (FieldArgumentInfo arg : fieldArguments.getOrDefault(childPath, List.of())) {
                allArguments.add(Argument.newArgument()
                    .name(arg.argumentName())
                    .value(VariableReference.newVariableReference()
                        .name(arg.variableName())
                        .build())
                    .build());
            }
            if (!allArguments.isEmpty()) {
                fieldBuilder.arguments(allArguments);
            }

            // Add directives (e.g., @skip, @include with variable conditions)
            if (!child.queryDirectives.isEmpty()) {
                fieldBuilder.directives(child.queryDirectives);
            }

            // For opaque fields, build selection set from the original selection tree
            SelectionSet childSelectionSet;
            if (child.isOpaque() && child.opaqueSelection.hasSubSelections()) {
                childSelectionSet = buildOpaqueSelectionSet(child.opaqueSelection.subSelections());
            } else {
                childSelectionSet = buildSelectionSet(child, childPath);
            }
            if (childSelectionSet != null) {
                fieldBuilder.selectionSet(childSelectionSet);
            }

            selections.add(fieldBuilder.build());
        }

        for (SelectionNode.Fragment fragment : node.inlineFragments) {
            InlineFragment.Builder fragmentBuilder = InlineFragment.newInlineFragment();
            if (fragment.typeCondition != null) {
                fragmentBuilder.typeCondition(TypeName.newTypeName(fragment.typeCondition).build());
            }
            if (!fragment.directives.isEmpty()) {
                fragmentBuilder.directives(fragment.directives);
            }
            SelectionSet fragmentSelectionSet = buildSelectionSet(fragment.root, currentPath);
            if (fragmentSelectionSet != null) {
                fragmentBuilder.selectionSet(fragmentSelectionSet);
            }
            selections.add(fragmentBuilder.build());
        }

        return SelectionSet.newSelectionSet()
            .selections(selections)
            .build();
    }

    /**
     * Builds a SelectionSet from opaque selections (used for introspection fields).
     * Converts our Selection model back to graphql-java AST.
     */
    private SelectionSet buildOpaqueSelectionSet(List<Selection> selections) {
        if (selections.isEmpty()) {
            return null;
        }

        List<graphql.language.Selection<?>> astSelections = new ArrayList<>();

        for (Selection selection : selections) {
            if (selection instanceof FieldSelection fieldSel) {
                Field.Builder fieldBuilder = Field.newField()
                    .name(fieldSel.fieldName())
                    .alias(fieldSel.alias());
                if (!fieldSel.arguments().isEmpty()) {
                    fieldBuilder.arguments(fieldSel.arguments());
                }
                if (!fieldSel.directives().isEmpty()) {
                    fieldBuilder.directives(fieldSel.directives());
                }
                if (fieldSel.hasSubSelections()) {
                    SelectionSet subSet = buildOpaqueSelectionSet(fieldSel.subSelections());
                    if (subSet != null) {
                        fieldBuilder.selectionSet(subSet);
                    }
                }
                astSelections.add(fieldBuilder.build());
            } else if (selection instanceof InlineFragmentSelection inlineSel) {
                InlineFragment.Builder fragmentBuilder = InlineFragment.newInlineFragment();
                if (inlineSel.typeCondition() != null) {
                    fragmentBuilder.typeCondition(TypeName.newTypeName(inlineSel.typeCondition()).build());
                }
                if (!inlineSel.directives().isEmpty()) {
                    fragmentBuilder.directives(inlineSel.directives());
                }
                if (!inlineSel.subSelections().isEmpty()) {
                    SelectionSet subSet = buildOpaqueSelectionSet(inlineSel.subSelections());
                    if (subSet != null) {
                        fragmentBuilder.selectionSet(subSet);
                    }
                }
                astSelections.add(fragmentBuilder.build());
            }
        }

        return SelectionSet.newSelectionSet()
            .selections(astSelections)
            .build();
    }
}
