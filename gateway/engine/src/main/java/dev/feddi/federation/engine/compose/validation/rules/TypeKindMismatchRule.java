package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLUnionType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates that types with the same name across different subgraphs have the same kind.
 * For example, a type named "Product" cannot be an object in one schema and an interface in another.
 *
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Type-Kind-Mismatch
 */
public final class TypeKindMismatchRule implements ValidationRule {

    private static final String CODE = "TYPE_KIND_MISMATCH";

    private static final Set<String> BUILT_IN_TYPES = Set.of(
        "String", "Int", "Float", "Boolean", "ID",
        "__Schema", "__Type", "__Field", "__InputValue", "__EnumValue",
        "__TypeKind", "__Directive", "__DirectiveLocation"
    );

    @Override
    public ValidationPhase phase() {
        return ValidationPhase.PRE_MERGE;
    }

    @Override
    public String name() {
        return "TypeKindMismatchRule";
    }

    @Override
    public ValidationResult validate(List<Subgraph> subgraphs) {
        ValidationResult.Builder builder = ValidationResult.builder();

        // Collect all types by name across all subgraphs
        Map<String, List<TypeInfo>> typesByName = new LinkedHashMap<>();

        for (Subgraph subgraph : subgraphs) {
            for (GraphQLType type : subgraph.schema().getAllTypesAsList()) {
                String typeName = getTypeName(type);
                if (typeName == null || BUILT_IN_TYPES.contains(typeName)) {
                    continue;
                }

                typesByName
                    .computeIfAbsent(typeName, k -> new ArrayList<>())
                    .add(new TypeInfo(subgraph.name(), type, getTypeKind(type)));
            }
        }

        // Check for kind mismatches
        for (Map.Entry<String, List<TypeInfo>> entry : typesByName.entrySet()) {
            String typeName = entry.getKey();
            List<TypeInfo> typeInfos = entry.getValue();

            if (typeInfos.size() < 2) {
                continue; // Only one definition, no conflict possible
            }

            String firstKind = typeInfos.get(0).kind;
            for (int i = 1; i < typeInfos.size(); i++) {
                TypeInfo current = typeInfos.get(i);
                if (!firstKind.equals(current.kind)) {
                    TypeInfo first = typeInfos.get(0);
                    String message = String.format(
                        "Type '%s' has mismatched kinds: '%s' in schema '%s' vs '%s' in schema '%s'.",
                        typeName, first.kind, first.schemaName, current.kind, current.schemaName
                    );
                    builder.addError(CODE, message, typeName, null);
                    break; // One error per type is enough
                }
            }
        }

        return builder.build();
    }

    private String getTypeName(GraphQLType type) {
        if (type instanceof GraphQLNamedType named) {
            return named.getName();
        }
        return null;
    }

    private String getTypeKind(GraphQLType type) {
        if (type instanceof GraphQLScalarType) {
            return "SCALAR";
        } else if (type instanceof GraphQLObjectType) {
            return "OBJECT";
        } else if (type instanceof GraphQLInterfaceType) {
            return "INTERFACE";
        } else if (type instanceof GraphQLUnionType) {
            return "UNION";
        } else if (type instanceof GraphQLEnumType) {
            return "ENUM";
        } else if (type instanceof GraphQLInputObjectType) {
            return "INPUT_OBJECT";
        }
        return "UNKNOWN";
    }

    private record TypeInfo(String schemaName, GraphQLType type, String kind) {}
}
