package dev.feddi.federation.engine.executor;

import graphql.language.Field;
import graphql.language.InlineFragment;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import graphql.schema.GraphQLCompositeType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fields of a response object, in the order of the operation's selection set, compiled once per operation for
 * the {@link ResponseWriter}: for each response key its field definition (null for fields the type does not define,
 * such as introspection fields, which are written as they are), the key as JSON bytes and the shape of the field's
 * objects.
 *
 * <p>Works on a normalized operation (named fragments inlined). Fields of inline fragments are looked up on the
 * fragment's type condition and merged by response key at their first occurrence; a field of a fragment that does
 * not apply to an object is absent from it.
 */
public final class ResponseShape {

    final String[] keys;
    final byte[][] keyBytes;
    final @Nullable GraphQLType[] types;
    final String[] coordinates;
    final @Nullable ResponseShape[] children;

    private ResponseShape(List<Entry> entries) {
        int size = entries.size();
        keys = new String[size];
        keyBytes = new byte[size][];
        types = new GraphQLType[size];
        coordinates = new String[size];
        children = new ResponseShape[size];
        for (int i = 0; i < size; i++) {
            Entry entry = entries.get(i);
            keys[i] = entry.key;
            keyBytes[i] = keyBytes(entry.key);
            types[i] = entry.definition == null ? null : entry.definition.getType();
            coordinates[i] = entry.definition == null ? entry.key : entry.parentType + "." + entry.definition.getName();
            children[i] = entry.child;
        }
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
        Map<String, Entry> fields = new LinkedHashMap<>();
        for (SelectionSet set : sets) {
            collect(schema, type, set, fields);
        }
        for (Entry entry : fields.values()) {
            if (entry.definition != null && !entry.selections.isEmpty()
                && GraphQLTypeUtil.unwrapAll(entry.definition.getType()) instanceof GraphQLCompositeType composite) {
                entry.child = compile(schema, composite, entry.selections);
            }
        }
        return new ResponseShape(new ArrayList<>(fields.values()));
    }

    private static void collect(GraphQLSchema schema, GraphQLCompositeType type, SelectionSet selectionSet,
                                Map<String, Entry> fields) {
        for (Selection<?> selection : selectionSet.getSelections()) {
            if (selection instanceof Field field) {
                GraphQLFieldDefinition definition = type instanceof GraphQLFieldsContainer container
                    ? container.getFieldDefinition(field.getName()) : null;
                Entry entry = fields.computeIfAbsent(field.getResultKey(),
                    key -> new Entry(key, definition, type.getName()));
                if (field.getSelectionSet() != null) {
                    entry.selections.add(field.getSelectionSet());
                }
            } else if (selection instanceof InlineFragment fragment) {
                GraphQLCompositeType fragmentType = type;
                if (fragment.getTypeCondition() != null
                    && schema.getType(fragment.getTypeCondition().getName()) instanceof GraphQLCompositeType c) {
                    fragmentType = c;
                }
                collect(schema, fragmentType, fragment.getSelectionSet(), fields);
            }
        }
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
        final @Nullable GraphQLFieldDefinition definition;
        final String parentType;
        final List<SelectionSet> selections = new ArrayList<>();
        @Nullable ResponseShape child;

        Entry(String key, @Nullable GraphQLFieldDefinition definition, String parentType) {
            this.key = key;
            this.definition = definition;
            this.parentType = parentType;
        }
    }
}
