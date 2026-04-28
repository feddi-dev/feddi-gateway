package dev.feddi.federation.engine;

import graphql.introspection.Introspection;

/**
 * Constants for GraphQL introspection field names.
 *
 * Use these constants instead of hardcoded strings like "__typename", "__type", "__schema".
 * This ensures consistency and allows compile-time checking.
 *
 * The values are derived from GraphQL Java's Introspection class to ensure they match
 * the actual field definitions.
 */
public final class IntrospectionFields {

    private IntrospectionFields() {
        // Utility class
    }

    /**
     * The __typename meta-field name.
     * Available on all object types to get the concrete type name at runtime.
     */
    public static final String TYPENAME = Introspection.TypeNameMetaFieldDef.getName();

    /**
     * The __type meta-field name.
     * Available on the Query root type for type introspection.
     */
    public static final String TYPE = Introspection.TypeMetaFieldDef.getName();

    /**
     * The __schema meta-field name.
     * Available on the Query root type for schema introspection.
     */
    public static final String SCHEMA = Introspection.SchemaMetaFieldDef.getName();
}
