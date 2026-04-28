package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.feddi.federation.engine.compose.FederationDirectives.EXTERNAL;

/**
 * Validates that @external fields have the same type as their base definition.
 *
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-External-Type-Mismatch
 */
public final class ExternalTypeMismatchRule implements ValidationRule {

    private static final String CODE = "EXTERNAL_TYPE_MISMATCH";

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
        return "ExternalTypeMismatchRule";
    }

    @Override
    public ValidationResult validate(List<Subgraph> subgraphs) {
        ValidationResult.Builder builder = ValidationResult.builder();

        // Collect all fields by coordinate across all subgraphs
        Map<String, List<FieldInfo>> fieldsByCoordinate = new LinkedHashMap<>();

        for (Subgraph subgraph : subgraphs) {
            for (GraphQLType type : subgraph.schema().getAllTypesAsList()) {
                if (type instanceof GraphQLObjectType objType) {
                    collectFields(objType, subgraph.name(), fieldsByCoordinate);
                } else if (type instanceof GraphQLInterfaceType ifaceType) {
                    collectFields(ifaceType, subgraph.name(), fieldsByCoordinate);
                }
            }
        }

        // Check for external type mismatches
        for (Map.Entry<String, List<FieldInfo>> entry : fieldsByCoordinate.entrySet()) {
            String coordinate = entry.getKey();
            List<FieldInfo> fieldInfos = entry.getValue();

            List<FieldInfo> externalFields = fieldInfos.stream().filter(f -> f.isExternal).toList();
            List<FieldInfo> nonExternalFields = fieldInfos.stream().filter(f -> !f.isExternal).toList();

            if (externalFields.isEmpty() || nonExternalFields.isEmpty()) {
                continue;
            }

            // Get the base type (from non-external field)
            String baseType = GraphQLTypeUtil.simplePrint(nonExternalFields.get(0).fieldType);

            // Check each external field matches the base type exactly
            for (FieldInfo external : externalFields) {
                String externalType = GraphQLTypeUtil.simplePrint(external.fieldType);
                if (!externalType.equals(baseType)) {
                    String message = String.format(
                        "Field '%s' has @external type '%s' in schema '%s' but base type is '%s'.",
                        coordinate, externalType, external.schemaName, baseType
                    );
                    builder.addError(CODE, message, coordinate, external.schemaName);
                }
            }
        }

        return builder.build();
    }

    private void collectFields(GraphQLFieldsContainer type, String schemaName,
                               Map<String, List<FieldInfo>> fieldsByCoordinate) {
        String typeName = ((GraphQLNamedType) type).getName();
        if (BUILT_IN_TYPES.contains(typeName)) {
            return;
        }

        for (GraphQLFieldDefinition field : type.getFieldDefinitions()) {
            String coordinate = typeName + "." + field.getName();
            boolean isExternal = field.hasAppliedDirective(EXTERNAL);
            fieldsByCoordinate
                .computeIfAbsent(coordinate, k -> new ArrayList<>())
                .add(new FieldInfo(schemaName, isExternal, field.getType()));
        }
    }

    private record FieldInfo(String schemaName, boolean isExternal, GraphQLOutputType fieldType) {}
}
