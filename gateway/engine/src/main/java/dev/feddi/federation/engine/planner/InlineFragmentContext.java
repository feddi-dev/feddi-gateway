package dev.feddi.federation.engine.planner;

import dev.feddi.federation.engine.parser.FieldSelectionMap.Path;
import dev.feddi.federation.engine.parser.FieldSelectionMap.PathSegment;
import graphql.language.Argument;
import graphql.language.Directive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The inline fragment a client selection is planned into, and the position within it (the field names entered
 * since the fragment) for nested fields.
 */
final class InlineFragmentContext {
    final SelectionNode.Fragment fragmentNode;
    final String subgraph;                               // subgraph of the plan that holds the fragment
    final List<String> currentPath = new ArrayList<>();  // Path within the fragment

    InlineFragmentContext(SelectionNode.Fragment fragmentNode, String subgraph) {
        this.fragmentNode = fragmentNode;
        this.subgraph = subgraph;
    }

    void addField(String alias, String fieldName, boolean hasChildren, List<Argument> arguments,
                  List<Directive> directives) {
        addField(alias, fieldName, hasChildren, arguments, directives, FieldOrigin.REQUESTED);
    }

    void addField(String alias, String fieldName, boolean hasChildren, List<Argument> arguments,
                  List<Directive> directives, FieldOrigin origin) {
        fragmentNode.addFieldAtPath(alias, fieldName, currentPath, hasChildren, arguments, directives, origin);
    }

    void enterField(String fieldName) {
        currentPath.add(fieldName);
    }

    void exitField() {
        if (!currentPath.isEmpty()) {
            currentPath.remove(currentPath.size() - 1);
        }
    }

    /** Selects a key path (e.g. {@code id} or {@code product.id}) at the current position. */
    Map<String, String> addKeyPath(Path keyPath) {
        Map<String, String> responseKeys = new LinkedHashMap<>();
        List<PathSegment> segments = keyPath.segments();
        for (int i = 0; i < segments.size(); i++) {
            String segment = segments.get(i).fieldName();
            boolean hasChildren = i < segments.size() - 1;
            addField(null, segment, hasChildren, List.of(), List.of(), FieldOrigin.ARTIFICIAL_KEY);
            responseKeys.put(segment, segment);
            if (hasChildren) {
                enterField(segment);
            }
        }
        for (int i = 0; i < segments.size() - 1; i++) {
            exitField();
        }
        return responseKeys;
    }
}
