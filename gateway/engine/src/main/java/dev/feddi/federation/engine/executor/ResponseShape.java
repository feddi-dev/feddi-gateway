package dev.feddi.federation.engine.executor;

import graphql.language.Field;
import graphql.language.InlineFragment;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import graphql.schema.GraphQLCompositeType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnionType;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The fields of a response object, in the order of the operation's selection set, compiled once per operation for
 * the {@link ResponseWriter}: for each response key its field definition (null for fields the type does not define,
 * such as introspection fields, which are written as they are), the key as JSON bytes and the shape of the field's
 * objects.
 *
 * <p>Works on a normalized operation (named fragments inlined). Fields of inline fragments are looked up on the
 * fragment's type condition and merged by response key at their first occurrence. A field that is selected for some
 * of the object types only (in a fragment on one of them) knows these types: the writer leaves it out of objects of
 * other types, which may still have it as a field the planner added for its own use (e.g. a key). A response key
 * that fragments on different object types select with different field types has a variant per type.
 */
public final class ResponseShape {

    final String[] keys;
    final byte[][] keyBytes;
    /** The keys as {@code ,"key":}, for the fields after the first. */
    final byte[][] nextKeyBytes;
    /** The keys as <code>{"key":</code>, for the first field of an object. */
    final byte[][] firstKeyBytes;
    final @Nullable GraphQLType[] types;
    final String[] coordinates;
    final @Nullable ResponseShape[] children;
    /** For each field, the object types it is selected for; null if it is selected for every object. */
    final @Nullable Set<String>[] onlyFor;
    /** For each field, its variants by object type if fragments select it with different types; null mostly. */
    final Variant @Nullable [][] variants;
    /** Whether a field depends on the object's type ({@link #onlyFor} or {@link #variants}). */
    final boolean typeDependent;
    /**
     * About the size of the responses written for this shape so far, in bytes: the initial size of the next one's
     * buffer, which otherwise grows (and is copied) several times for a large response. Read and written without
     * synchronization, as it is only a hint.
     */
    int sizeHint;

    /** A field's type, shape and coordinate for some object types. */
    record Variant(Set<String> objectTypes, @Nullable GraphQLType type, @Nullable ResponseShape child,
                   String coordinate) {
    }

    @SuppressWarnings("unchecked")
    private ResponseShape(List<Entry> entries, Set<String> possibleTypes) {
        int size = entries.size();
        keys = new String[size];
        keyBytes = new byte[size][];
        nextKeyBytes = new byte[size][];
        firstKeyBytes = new byte[size][];
        types = new GraphQLType[size];
        coordinates = new String[size];
        children = new ResponseShape[size];
        onlyFor = new Set[size];
        variants = new Variant[size][];
        boolean dependent = false;
        for (int i = 0; i < size; i++) {
            Entry entry = entries.get(i);
            // Interned like the field names of parsed responses, so lookups mostly match by reference
            keys[i] = entry.key.intern();
            keyBytes[i] = keyBytes(entry.key);
            nextKeyBytes[i] = new byte[keyBytes[i].length + 1];
            nextKeyBytes[i][0] = ',';
            System.arraycopy(keyBytes[i], 0, nextKeyBytes[i], 1, keyBytes[i].length);
            firstKeyBytes[i] = nextKeyBytes[i].clone();
            firstKeyBytes[i][0] = '{';
            List<Variant> built = entry.variants.values().stream().map(VariantBuilder::build).toList();
            Variant first = built.get(0);
            types[i] = first.type();
            coordinates[i] = first.coordinate();
            children[i] = first.child();
            if (built.size() > 1) {
                variants[i] = built.toArray(new Variant[0]);
                dependent = true;
            }
            if (!entry.objectTypes.containsAll(possibleTypes)) {
                onlyFor[i] = Set.copyOf(entry.objectTypes);
                dependent = true;
            }
        }
        typeDependent = dependent;
    }

    /**
     * Compiles the shape of an operation's response data.
     *
     * @throws IllegalArgumentException if the schema has no root type for the operation
     */
    public static ResponseShape compile(GraphQLSchema schema, OperationDefinition operation) {
        GraphQLCompositeType rootType = switch (operation.getOperation()) {
            case QUERY -> schema.getQueryType();
            case MUTATION -> schema.getMutationType();
            case SUBSCRIPTION -> schema.getSubscriptionType();
        };
        if (rootType == null) {
            throw new IllegalArgumentException("The schema has no root type for " + operation.getOperation());
        }
        return compile(schema, rootType, List.of(operation.getSelectionSet()));
    }

    private static ResponseShape compile(GraphQLSchema schema, GraphQLCompositeType type, List<SelectionSet> sets) {
        Set<String> possibleTypes = possibleTypes(schema, type);
        Map<String, Entry> fields = new LinkedHashMap<>();
        for (SelectionSet set : sets) {
            collect(schema, type, set, possibleTypes, fields);
        }
        for (Entry entry : fields.values()) {
            for (VariantBuilder variant : entry.variants.values()) {
                if (variant.definition != null && !variant.selections.isEmpty()
                    && GraphQLTypeUtil.unwrapAll(variant.definition.getType()) instanceof GraphQLCompositeType composite) {
                    variant.child = compile(schema, composite, variant.selections);
                }
            }
        }
        return new ResponseShape(new ArrayList<>(fields.values()), possibleTypes);
    }

    /**
     * Collects the fields of a selection set on a type.
     *
     * @param scope the object types the selection set applies to (narrowed by fragments)
     */
    private static void collect(GraphQLSchema schema, GraphQLCompositeType type, SelectionSet selectionSet,
                                Set<String> scope, Map<String, Entry> fields) {
        for (Selection<?> selection : selectionSet.getSelections()) {
            if (selection instanceof Field field) {
                GraphQLFieldDefinition definition = type instanceof GraphQLFieldsContainer container
                    ? container.getFieldDefinition(field.getName()) : null;
                Entry entry = fields.computeIfAbsent(field.getResultKey(), Entry::new);
                entry.objectTypes.addAll(scope);
                String variantKey = definition == null ? "" : GraphQLTypeUtil.simplePrint(definition.getType());
                VariantBuilder variant = entry.variants.computeIfAbsent(variantKey,
                    key -> new VariantBuilder(entry.key, definition, type.getName()));
                variant.objectTypes.addAll(scope);
                if (field.getSelectionSet() != null) {
                    variant.selections.add(field.getSelectionSet());
                }
            } else if (selection instanceof InlineFragment fragment) {
                GraphQLCompositeType fragmentType = type;
                Set<String> fragmentScope = scope;
                if (fragment.getTypeCondition() != null
                    && schema.getType(fragment.getTypeCondition().getName()) instanceof GraphQLCompositeType c) {
                    fragmentType = c;
                    fragmentScope = new HashSet<>(scope);
                    fragmentScope.retainAll(possibleTypes(schema, c));
                }
                if (!fragmentScope.isEmpty()) {
                    collect(schema, fragmentType, fragment.getSelectionSet(), fragmentScope, fields);
                }
            }
        }
    }

    /** The names of the object types a value of a composite type can have. */
    private static Set<String> possibleTypes(GraphQLSchema schema, GraphQLCompositeType type) {
        Set<String> names = new HashSet<>();
        switch (type) {
            case GraphQLObjectType object -> names.add(object.getName());
            case GraphQLInterfaceType iface -> schema.getImplementations(iface).forEach(o -> names.add(o.getName()));
            case GraphQLUnionType union -> union.getTypes().forEach(t -> names.add(((GraphQLNamedType) t).getName()));
            default -> names.add(type.getName());
        }
        return names;
    }

    /** The key as {@code "key":} in UTF-8. */
    private static byte[] keyBytes(String key) {
        JsonBuffer buffer = new JsonBuffer(key.length() + 3);
        buffer.writeString(key);
        buffer.write((byte) ':');
        return buffer.toByteArray();
    }

    private static final class Entry {
        final String key;
        /** The object types the field is selected for. */
        final Set<String> objectTypes = new HashSet<>();
        /** By the field's type (empty for fields without definition). */
        final Map<String, VariantBuilder> variants = new LinkedHashMap<>();

        Entry(String key) {
            this.key = key;
        }
    }

    private static final class VariantBuilder {
        final String key;
        final @Nullable GraphQLFieldDefinition definition;
        final String parentType;
        final Set<String> objectTypes = new HashSet<>();
        final List<SelectionSet> selections = new ArrayList<>();
        @Nullable ResponseShape child;

        VariantBuilder(String key, @Nullable GraphQLFieldDefinition definition, String parentType) {
            this.key = key;
            this.definition = definition;
            this.parentType = parentType;
        }

        Variant build() {
            return new Variant(Set.copyOf(objectTypes), definition == null ? null : definition.getType(), child,
                definition == null ? key : parentType + "." + definition.getName());
        }
    }
}
