package dev.feddi.federation.engine.planner;

import dev.feddi.federation.engine.query.FieldSelection;
import graphql.language.Argument;
import graphql.language.AstPrinter;
import graphql.language.Directive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A field in a subgraph operation being built (or the operation's root), with its child fields keyed by response
 * key and its inline fragments.
 */
final class SelectionNode {
    final String fieldName;
    final boolean hasChildren;
    final Map<String, SelectionNode> children = new LinkedHashMap<>();
    final List<Fragment> inlineFragments = new ArrayList<>();
    String alias;  // The alias for this field (null if no alias)
    List<Argument> queryArguments = List.of();
    List<Directive> queryDirectives = List.of();
    FieldOrigin origin = FieldOrigin.REQUESTED;  // Default to requested

    // For opaque fields (like introspection), stores the original selection with all sub-selections
    FieldSelection opaqueSelection;

    SelectionNode(String fieldName, boolean hasChildren) {
        this.fieldName = fieldName;
        this.hasChildren = hasChildren;
    }

    SelectionNode(String fieldName, boolean hasChildren, FieldOrigin origin) {
        this.fieldName = fieldName;
        this.hasChildren = hasChildren;
        this.origin = origin;
    }

    /** The root of an operation's or a fragment's selections. */
    static SelectionNode root() {
        return new SelectionNode("", true);
    }

    /**
     * Returns the response key for this field (alias if present, otherwise field name).
     */
    String responseKey() {
        return alias != null ? alias : fieldName;
    }

    /**
     * Updates the origin, but REQUESTED always wins over ARTIFICIAL.
     * This handles the case where a field is both requested and needed internally.
     */
    void updateOrigin(FieldOrigin newOrigin) {
        if (this.origin == FieldOrigin.REQUESTED) {
            return;
        }
        this.origin = newOrigin;
    }

    void setQueryArguments(List<Argument> arguments) {
        if (arguments != null && !arguments.isEmpty()) {
            this.queryArguments = arguments;
        }
    }

    void setQueryDirectives(List<Directive> directives) {
        if (directives != null && !directives.isEmpty()) {
            this.queryDirectives = directives;
        }
    }

    boolean isOpaque() {
        return opaqueSelection != null;
    }

    /**
     * Checks if a child with the given response key already exists.
     * Used to detect naming clashes when adding artificial fields.
     */
    boolean hasChildWithResponseKey(String responseKey) {
        return children.values().stream().anyMatch(child -> responseKey.equals(child.responseKey()));
    }

    /**
     * The child at a response key; else, with {@code matchFieldName}, the last child selecting a field of that name
     * (a path of field names into aliased fields); else the field inside the inline fragments (see
     * {@link #fragmentChild}). Null if there is none.
     */
    SelectionNode child(String responseKey, boolean matchFieldName) {
        SelectionNode child = children.get(responseKey);
        if (child == null && matchFieldName) {
            for (SelectionNode candidate : children.values()) {
                if (candidate.fieldName.equals(responseKey)) {
                    child = candidate;
                }
            }
        }
        return child != null ? child : fragmentChild(responseKey);
    }

    /**
     * The node at a path of response keys below this one (see {@link #child}); with {@code create}, missing nodes
     * are created as intermediate fields of internal paths, otherwise null is returned.
     */
    SelectionNode descend(List<String> path, boolean matchFieldNames, boolean create) {
        SelectionNode node = this;
        for (String segment : path) {
            SelectionNode child = node.child(segment, matchFieldNames);
            if (child == null) {
                if (!create) {
                    return null;
                }
                child = new SelectionNode(segment, true);
                node.children.put(segment, child);
            }
            node = child;
        }
        return node;
    }

    /**
     * The field with this response key inside the node's inline fragments, when exactly one of them selects it: a
     * response path through a union or interface field ({@code searchProduct.product}) continues in the fragment
     * that selects it ({@code ... on ProductSearchResult { product }}).
     */
    private SelectionNode fragmentChild(String responseKey) {
        SelectionNode found = null;
        for (Fragment fragment : inlineFragments) {
            SelectionNode child = fragment.root.children.get(responseKey);
            if (child == null) {
                child = fragment.root.fragmentChild(responseKey);
            }
            if (child != null) {
                if (found != null) {
                    return null;
                }
                found = child;
            }
        }
        return found;
    }

    /**
     * Adds a field under its response key, or updates the field already there: REQUESTED wins over an artificial
     * origin, and an alias, arguments and directives are only set if the field has none yet.
     */
    void addField(String alias, String fieldName, boolean hasChildren, List<Argument> arguments,
                  List<Directive> directives, FieldOrigin origin) {
        String responseKey = alias != null ? alias : fieldName;
        SelectionNode fieldNode = children.get(responseKey);
        if (fieldNode == null) {
            fieldNode = new SelectionNode(fieldName, hasChildren, origin);
            children.put(responseKey, fieldNode);
        }
        fieldNode.updateOrigin(origin);
        if (alias != null && fieldNode.alias == null) {
            fieldNode.alias = alias;
        }
        if (fieldNode.queryArguments.isEmpty()) {
            fieldNode.setQueryArguments(arguments);
        }
        if (fieldNode.queryDirectives.isEmpty()) {
            fieldNode.setQueryDirectives(directives);
        }
    }

    /**
     * Adds an artificial field (for @key or @require) selected with {@code arguments}, or reuses the field with the
     * same name and arguments, unless that one has directives (a key the client selects with
     * {@code @skip(if: $flag)} may be missing). It gets an alias if {@code alias} is set, if it has arguments (a
     * field the client selects later without them must not merge into it), or if another field has its name as
     * response key.
     *
     * @return the response key where the value will be found
     */
    String addArtificialField(String fieldName, List<Argument> arguments, boolean hasChildren, FieldOrigin origin,
                              boolean alias) {
        // The same field without alias if there is one (the same requirement always gets the same response key)
        SelectionNode existing = null;
        for (SelectionNode child : children.values()) {
            if (child.fieldName.equals(fieldName) && child.queryDirectives.isEmpty()
                && printedArguments(child.queryArguments).equals(printedArguments(arguments))
                && (existing == null || existing.alias != null)) {
                existing = child;
            }
        }
        if (existing != null) {
            // Reuse it; a REQUESTED field stays REQUESTED
            return existing.responseKey();
        }
        SelectionNode fieldNode = new SelectionNode(fieldName, hasChildren, origin);
        if (alias || !arguments.isEmpty() || hasChildWithResponseKey(fieldName)) {
            fieldNode.alias = uniqueAlias(fieldName);
        }
        fieldNode.setQueryArguments(arguments);
        children.put(fieldNode.responseKey(), fieldNode);
        return fieldNode.responseKey();
    }

    /**
     * Generates a unique internal alias for an artificial field.
     * Format: __gql_{fieldName} or __gql_{fieldName}_{n} if that's taken
     */
    private String uniqueAlias(String fieldName) {
        String base = "__gql_" + fieldName;
        String candidate = base;
        int counter = 0;
        while (hasChildWithResponseKey(candidate)) {
            counter++;
            candidate = base + "_" + counter;
        }
        return candidate;
    }

    /**
     * Gets or creates an inline fragment node with the given type condition and directives.
     */
    Fragment getOrCreateInlineFragment(String typeCondition, List<Directive> directives) {
        List<Directive> wanted = directives != null ? directives : List.of();
        for (Fragment fragment : inlineFragments) {
            if (Objects.equals(typeCondition, fragment.typeCondition)
                && printed(wanted).equals(printed(fragment.directives))) {
                return fragment;
            }
        }
        Fragment fragment = new Fragment(typeCondition, directives);
        inlineFragments.add(fragment);
        return fragment;
    }

    /** Arguments as sorted "name: value" texts, to compare argument lists. */
    static List<String> printedArguments(List<Argument> arguments) {
        return arguments.stream()
            .map(argument -> argument.getName() + ": " + AstPrinter.printAstCompact(argument.getValue()))
            .sorted()
            .toList();
    }

    private static List<String> printed(List<Directive> directives) {
        return directives.stream().map(AstPrinter::printAstCompact).toList();
    }

    /**
     * An inline fragment in the selection tree: its type condition, directives and selections.
     */
    static final class Fragment {
        final String typeCondition;
        final List<Directive> directives;
        final SelectionNode root;

        Fragment(String typeCondition, List<Directive> directives) {
            this.typeCondition = typeCondition;
            this.directives = directives != null ? directives : List.of();
            this.root = SelectionNode.root();
        }

        /** A fragment without type condition whose fields go directly into {@code selections}. */
        Fragment(SelectionNode selections) {
            this.typeCondition = null;
            this.directives = List.of();
            this.root = selections;
        }

        /**
         * Adds a field at a path of field names within this fragment's selections (e.g. "id" under
         * "suggestedArticles" for {@code ... on Article { suggestedArticles { id } }}).
         */
        void addFieldAtPath(String alias, String fieldName, List<String> path, boolean hasChildren,
                            List<Argument> arguments, List<Directive> fieldDirectives, FieldOrigin origin) {
            SelectionNode parent = root.descend(path, true, false);
            if (parent == null) {
                throw new PlanningException("Parent node not found in path " + path + " while adding field '"
                    + fieldName + "'");
            }
            parent.addField(alias, fieldName, hasChildren, arguments, fieldDirectives, origin);
        }
    }
}
