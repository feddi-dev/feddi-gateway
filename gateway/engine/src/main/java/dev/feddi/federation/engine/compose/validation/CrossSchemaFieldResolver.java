package dev.feddi.federation.engine.compose.validation;

import dev.feddi.federation.engine.compose.Subgraph;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedOutputType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnionType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Utility for resolving fields across multiple schemas.
 * Used by IS_INVALID_FIELDS and REQUIRE_INVALID_FIELDS validation rules.
 *
 * These rules need to validate that field references in @is and @require
 * directives can be resolved from OTHER schemas (not the defining schema).
 */
public final class CrossSchemaFieldResolver {

    private static final Set<String> BUILT_IN_TYPES = Set.of(
        "String", "Int", "Float", "Boolean", "ID",
        "__Schema", "__Type", "__Field", "__InputValue", "__EnumValue",
        "__TypeKind", "__Directive", "__DirectiveLocation"
    );

    /**
     * Builds a map of type name -> field names from all schemas except the excluded one.
     * This represents the combined view of fields available from "other" schemas.
     *
     * @param subgraphs All subgraphs
     * @param excludeSchema Schema name to exclude (the "current" schema)
     * @return Map of type name to set of field names available from other schemas
     */
    public static Map<String, Set<String>> buildCrossSchemaFieldMap(
            List<Subgraph> subgraphs,
            String excludeSchema) {

        Map<String, Set<String>> result = new HashMap<>();

        for (Subgraph subgraph : subgraphs) {
            if (subgraph.name().equals(excludeSchema)) {
                continue; // Skip the excluded schema
            }

            for (GraphQLType type : subgraph.schema().getAllTypesAsList()) {
                if (type instanceof GraphQLFieldsContainer fieldsContainer) {
                    String typeName = ((GraphQLNamedType) type).getName();
                    if (BUILT_IN_TYPES.contains(typeName)) {
                        continue;
                    }

                    // Note: We include @internal types and fields because they CAN be
                    // referenced by @is and @require directives for internal resolution.
                    // @internal only excludes them from the public API, not from federation.

                    Set<String> fieldNames = result.computeIfAbsent(typeName, k -> new HashSet<>());

                    for (GraphQLFieldDefinition field : fieldsContainer.getFieldDefinitions()) {
                        fieldNames.add(field.getName());
                    }
                }
            }
        }

        return result;
    }

    /**
     * Builds a map of type name -> field name -> field type from all schemas except the excluded one.
     * This is used for nested path resolution where we need to know the return type of each field.
     *
     * @param subgraphs All subgraphs
     * @param excludeSchema Schema name to exclude
     * @return Map of type name to map of field name to unwrapped return type name
     */
    public static Map<String, Map<String, String>> buildCrossSchemaFieldTypeMap(
            List<Subgraph> subgraphs,
            String excludeSchema) {

        Map<String, Map<String, String>> result = new HashMap<>();

        for (Subgraph subgraph : subgraphs) {
            if (subgraph.name().equals(excludeSchema)) {
                continue;
            }

            for (GraphQLType type : subgraph.schema().getAllTypesAsList()) {
                if (type instanceof GraphQLFieldsContainer fieldsContainer) {
                    String typeName = ((GraphQLNamedType) type).getName();
                    if (BUILT_IN_TYPES.contains(typeName)) {
                        continue;
                    }

                    // Note: We include @internal types and fields because they CAN be
                    // referenced by @is and @require directives for internal resolution.

                    Map<String, String> fieldTypes = result.computeIfAbsent(typeName, k -> new HashMap<>());

                    for (GraphQLFieldDefinition field : fieldsContainer.getFieldDefinitions()) {
                        GraphQLType fieldType = GraphQLTypeUtil.unwrapAll(field.getType());
                        if (fieldType instanceof GraphQLNamedType namedType) {
                            fieldTypes.put(field.getName(), namedType.getName());
                        }
                    }
                }
            }
        }

        return result;
    }

    /**
     * Checks if a field exists in the cross-schema context (excluding current schema).
     * Also checks implementing types for interfaces and union members for unions.
     *
     * @param typeName The type to check
     * @param fieldName The field to look for
     * @param allSubgraphs All subgraphs
     * @param excludeSchema Schema to exclude
     * @return true if the field exists in at least one other schema
     */
    public static boolean isFieldInOtherSchemasWithSubtypes(
            String typeName,
            String fieldName,
            List<Subgraph> allSubgraphs,
            String excludeSchema) {

        for (Subgraph subgraph : allSubgraphs) {
            if (subgraph.name().equals(excludeSchema)) {
                continue;
            }
            if (isFieldInSchemaOrSubtypes(subgraph, typeName, fieldName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a field exists in the cross-schema context (simple map-based version).
     *
     * @param typeName The type to check
     * @param fieldName The field to look for
     * @param crossSchemaFields Map from buildCrossSchemaFieldMap
     * @return true if the field exists in at least one other schema
     */
    public static boolean isFieldInOtherSchemas(
            String typeName,
            String fieldName,
            Map<String, Set<String>> crossSchemaFields) {

        Set<String> fields = crossSchemaFields.get(typeName);
        return fields != null && fields.contains(fieldName);
    }

    /**
     * Checks if a field exists in the given schema.
     *
     * @param subgraph The subgraph to check
     * @param typeName The type name
     * @param fieldName The field name
     * @return true if the field exists in this schema
     */
    public static boolean isFieldInSchema(Subgraph subgraph, String typeName, String fieldName) {
        GraphQLType type = subgraph.schema().getType(typeName);
        if (type instanceof GraphQLFieldsContainer fieldsContainer) {
            return fieldsContainer.getFieldDefinition(fieldName) != null;
        }
        return false;
    }

    /**
     * Gets the return type of a field from the cross-schema context.
     *
     * @param typeName The parent type
     * @param fieldName The field name
     * @param crossSchemaFieldTypes Map from buildCrossSchemaFieldTypeMap
     * @return The unwrapped return type name, or null if not found
     */
    public static String getFieldReturnType(
            String typeName,
            String fieldName,
            Map<String, Map<String, String>> crossSchemaFieldTypes) {

        Map<String, String> fieldTypes = crossSchemaFieldTypes.get(typeName);
        if (fieldTypes != null) {
            return fieldTypes.get(fieldName);
        }
        return null;
    }

    /**
     * Checks if a type exists in any schema (used for type condition validation).
     *
     * @param subgraphs All subgraphs
     * @param typeName Type name to check
     * @return true if the type exists in at least one schema
     */
    public static boolean typeExistsInAnySchema(List<Subgraph> subgraphs, String typeName) {
        for (Subgraph subgraph : subgraphs) {
            if (subgraph.schema().getType(typeName) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a field exists in ANY schema (including the current one).
     * Used by @is validation which allows referencing fields from any schema.
     * Also checks implementing types for interfaces and union members for unions.
     *
     * @param subgraphs All subgraphs
     * @param typeName The type to check
     * @param fieldName The field to look for
     * @return true if the field exists in at least one schema
     */
    public static boolean isFieldInAnySchema(List<Subgraph> subgraphs, String typeName, String fieldName) {
        for (Subgraph subgraph : subgraphs) {
            if (isFieldInSchemaOrSubtypes(subgraph, typeName, fieldName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a field exists on a type or any of its implementing types.
     * For interfaces, checks all implementing object types.
     * For unions, checks all member types.
     */
    private static boolean isFieldInSchemaOrSubtypes(Subgraph subgraph, String typeName, String fieldName) {
        // First check the type directly
        if (isFieldInSchema(subgraph, typeName, fieldName)) {
            return true;
        }

        // For interfaces, check implementing types
        GraphQLType type = subgraph.schema().getType(typeName);
        if (type instanceof GraphQLInterfaceType interfaceType) {
            for (GraphQLType implType : subgraph.schema().getAllTypesAsList()) {
                if (implType instanceof GraphQLObjectType objectType) {
                    if (objectType.getInterfaces().contains(interfaceType)) {
                        if (isFieldInSchema(subgraph, objectType.getName(), fieldName)) {
                            return true;
                        }
                    }
                }
            }
        }

        // For unions, check member types
        if (type instanceof GraphQLUnionType unionType) {
            for (GraphQLNamedOutputType memberType : unionType.getTypes()) {
                if (isFieldInSchema(subgraph, memberType.getName(), fieldName)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Gets the return type of a field from any schema.
     *
     * @param subgraphs All subgraphs
     * @param typeName The parent type
     * @param fieldName The field name
     * @return The unwrapped return type name, or null if not found
     */
    public static String getFieldReturnTypeFromAnySchema(List<Subgraph> subgraphs, String typeName, String fieldName) {
        for (Subgraph subgraph : subgraphs) {
            GraphQLType type = subgraph.schema().getType(typeName);
            if (type instanceof GraphQLFieldsContainer fieldsContainer) {
                GraphQLFieldDefinition field = fieldsContainer.getFieldDefinition(fieldName);
                if (field != null) {
                    GraphQLType fieldType = GraphQLTypeUtil.unwrapAll(field.getType());
                    if (fieldType instanceof GraphQLNamedType namedType) {
                        return namedType.getName();
                    }
                }
            }
        }
        return null;
    }

    /**
     * Gets all concrete types (object types) for a given type name across all schemas.
     * For object types, returns a set containing just that type.
     * For interfaces, returns all implementing object types.
     * For unions, returns all member types.
     *
     * @param subgraphs All subgraphs
     * @param typeName The type name to get concrete types for
     * @return Set of concrete type names
     */
    public static Set<String> getConcreteTypes(List<Subgraph> subgraphs, String typeName) {
        Set<String> concreteTypes = new HashSet<>();

        for (Subgraph subgraph : subgraphs) {
            GraphQLType type = subgraph.schema().getType(typeName);
            if (type == null) {
                continue;
            }

            if (type instanceof GraphQLObjectType objectType) {
                // Object type is itself a concrete type
                concreteTypes.add(objectType.getName());
            } else if (type instanceof GraphQLInterfaceType interfaceType) {
                // Find all implementing types
                for (GraphQLType implType : subgraph.schema().getAllTypesAsList()) {
                    if (implType instanceof GraphQLObjectType objectType) {
                        if (objectType.getInterfaces().contains(interfaceType)) {
                            concreteTypes.add(objectType.getName());
                        }
                    }
                }
            } else if (type instanceof GraphQLUnionType unionType) {
                // Add all member types
                for (GraphQLNamedOutputType memberType : unionType.getTypes()) {
                    concreteTypes.add(memberType.getName());
                }
            }
        }

        return concreteTypes;
    }

    /**
     * Checks if a type is abstract (interface or union).
     *
     * @param subgraphs All subgraphs
     * @param typeName The type name to check
     * @return true if the type is an interface or union
     */
    public static boolean isAbstractType(List<Subgraph> subgraphs, String typeName) {
        for (Subgraph subgraph : subgraphs) {
            GraphQLType type = subgraph.schema().getType(typeName);
            if (type instanceof GraphQLInterfaceType || type instanceof GraphQLUnionType) {
                return true;
            }
        }
        return false;
    }
}
