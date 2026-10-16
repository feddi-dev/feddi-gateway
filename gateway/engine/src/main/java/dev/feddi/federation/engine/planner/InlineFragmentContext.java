package dev.feddi.federation.engine.planner;

import graphql.language.Argument;

import java.util.ArrayList;
import java.util.List;

/**
 * The inline fragment a client selection is planned into, and the position within it (the field names entered
 * since the fragment) for nested fields.
 */
final class InlineFragmentContext {
    final SelectionNode.Fragment fragmentNode;
    final SubgraphPlan plan;                             // the plan that holds the fragment
    final String subgraph;                               // subgraph of the plan that holds the fragment
    final List<String> basePath;                         // the client's response path to the fragment
    final String typeName;                               // the type of the objects the fragment selects on
    final List<String> currentPath = new ArrayList<>();  // Path within the fragment

    InlineFragmentContext(SelectionNode.Fragment fragmentNode, SubgraphPlan plan, List<String> basePath,
                          String typeName) {
        this.fragmentNode = fragmentNode;
        this.plan = plan;
        this.subgraph = plan.subgraph;
        this.basePath = List.copyOf(basePath);
        this.typeName = typeName;
    }

    void addField(String alias, String fieldName, boolean hasChildren, List<Argument> arguments) {
        addField(alias, fieldName, hasChildren, arguments, FieldOrigin.REQUESTED);
    }

    void addField(String alias, String fieldName, boolean hasChildren, List<Argument> arguments,
                  FieldOrigin origin) {
        fragmentNode.addFieldAtPath(alias, fieldName, currentPath, hasChildren, arguments, origin);
    }

    void enterField(String fieldName) {
        currentPath.add(fieldName);
    }

    void exitField() {
        if (!currentPath.isEmpty()) {
            currentPath.remove(currentPath.size() - 1);
        }
    }
}
