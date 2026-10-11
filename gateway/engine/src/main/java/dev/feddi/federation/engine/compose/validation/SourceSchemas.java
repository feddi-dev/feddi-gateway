package dev.feddi.federation.engine.compose.validation;

import dev.feddi.federation.engine.compose.Subgraph;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedOutputType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnionType;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The types of a set of source schemas taken together, as FieldSelectionMaps are validated against them: all
 * schemas, or all but the one that declares a requirement ({@link #excluding}).
 */
public final class SourceSchemas {

    /** A field definition and the schema it is defined in. */
    public record FieldIn(GraphQLFieldDefinition definition, GraphQLSchema schema) {
    }

    private final List<Subgraph> subgraphs;

    private SourceSchemas(List<Subgraph> subgraphs) {
        this.subgraphs = List.copyOf(subgraphs);
    }

    public static SourceSchemas of(List<Subgraph> subgraphs) {
        return new SourceSchemas(subgraphs);
    }

    /** These schemas without the one named {@code schemaName}. */
    public SourceSchemas excluding(String schemaName) {
        return new SourceSchemas(subgraphs.stream().filter(subgraph -> !subgraph.name().equals(schemaName)).toList());
    }

    /** Whether one of the schemas defines the type. */
    public boolean hasType(String typeName) {
        return subgraphs.stream().anyMatch(subgraph -> subgraph.schema().getType(typeName) != null);
    }

    /**
     * The field as defined on the type itself (not only on a subtype) in the first schema that defines it, as a
     * FieldSelectionMap path requires (Appendix A, Path Field Selections: "fieldName must be defined on the current
     * type in scope"; a subtype's field needs a type condition, e.g. data<Bar>.bar); null if none does.
     */
    public FieldIn field(String typeName, String fieldName) {
        for (Subgraph subgraph : subgraphs) {
            if (subgraph.schema().getType(typeName) instanceof GraphQLFieldsContainer container
                && container.getFieldDefinition(fieldName) != null) {
                return new FieldIn(container.getFieldDefinition(fieldName), subgraph.schema());
            }
        }
        return null;
    }

    /** Whether one of the schemas defines the field on the type itself (see {@link #field}). */
    public boolean hasField(String typeName, String fieldName) {
        return field(typeName, fieldName) != null;
    }

    /** Whether one of the schemas defines the field on the type or on one of its possible types. */
    public boolean hasFieldOnTypeOrSubtype(String typeName, String fieldName) {
        if (hasField(typeName, fieldName)) {
            return true;
        }
        for (Subgraph subgraph : subgraphs) {
            for (String possibleType : possibleTypes(subgraph.schema(), typeName)) {
                if (subgraph.schema().getType(possibleType) instanceof GraphQLFieldsContainer container
                    && container.getFieldDefinition(fieldName) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The named return type of the field in the first schema that defines it on the type, or null. */
    public String fieldType(String typeName, String fieldName) {
        FieldIn field = field(typeName, fieldName);
        if (field == null) {
            return null;
        }
        GraphQLType type = GraphQLTypeUtil.unwrapAll(field.definition().getType());
        return type instanceof GraphQLNamedType named ? named.getName() : null;
    }

    /**
     * The object types of a type in all the schemas: the type itself for an object type, an interface's
     * implementations, a union's members.
     */
    public Set<String> concreteTypes(String typeName) {
        Set<String> concreteTypes = new HashSet<>();
        for (Subgraph subgraph : subgraphs) {
            GraphQLType type = subgraph.schema().getType(typeName);
            if (type instanceof GraphQLObjectType) {
                concreteTypes.add(typeName);
            } else if (type != null) {
                concreteTypes.addAll(possibleTypes(subgraph.schema(), typeName));
            }
        }
        return concreteTypes;
    }

    /** Whether the type is an interface or a union in one of the schemas. */
    public boolean isAbstract(String typeName) {
        return subgraphs.stream().map(subgraph -> subgraph.schema().getType(typeName))
            .anyMatch(type -> type instanceof GraphQLInterfaceType || type instanceof GraphQLUnionType);
    }

    private static List<String> possibleTypes(GraphQLSchema schema, String typeName) {
        GraphQLType type = schema.getType(typeName);
        if (type instanceof GraphQLInterfaceType interfaceType) {
            return schema.getImplementations(interfaceType).stream().map(GraphQLObjectType::getName).toList();
        }
        if (type instanceof GraphQLUnionType unionType) {
            return unionType.getTypes().stream().map(GraphQLNamedOutputType::getName).toList();
        }
        return List.of();
    }
}
