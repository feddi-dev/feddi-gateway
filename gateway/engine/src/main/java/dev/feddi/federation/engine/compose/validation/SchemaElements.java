package dev.feddi.federation.engine.compose.validation;

import graphql.introspection.Introspection;
import graphql.language.StringValue;
import graphql.schema.GraphQLAppliedDirectiveArgument;
import graphql.schema.idl.ScalarInfo;

/**
 * Helpers the composition rules share for reading source schemas.
 */
public final class SchemaElements {

    private SchemaElements() {
    }

    /** Whether a type is one every schema has: an introspection type or a scalar the GraphQL spec specifies. */
    public static boolean isBuiltInType(String typeName) {
        return Introspection.isIntrospectionTypes(typeName) || ScalarInfo.isGraphqlSpecifiedScalar(typeName);
    }

    /**
     * The text of a directive argument's value ({@code value} is the argument or its value): a string literal's
     * value, or the value as text; null for a missing argument or value.
     */
    public static String stringValue(Object value) {
        Object literal = value instanceof GraphQLAppliedDirectiveArgument argument ? argument.getValue() : value;
        if (literal instanceof StringValue stringValue) {
            return stringValue.getValue();
        }
        return literal != null ? literal.toString() : null;
    }
}
