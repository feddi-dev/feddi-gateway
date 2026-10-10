package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import graphql.introspection.Introspection;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLAppliedDirectiveArgument;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnionType;
import graphql.schema.idl.ScalarInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.feddi.federation.engine.compose.FederationDirectives.EXTERNAL;
import static dev.feddi.federation.engine.compose.FederationDirectives.INTERNAL;
import static dev.feddi.federation.engine.compose.FederationDirectives.IS;
import static dev.feddi.federation.engine.compose.FederationDirectives.KEY;
import static dev.feddi.federation.engine.compose.FederationDirectives.LOOKUP;
import static dev.feddi.federation.engine.compose.FederationDirectives.OVERRIDE;
import static dev.feddi.federation.engine.compose.FederationDirectives.SHAREABLE;

/**
 * Validates that fields defined in multiple subgraphs are marked as @shareable.
 * 
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Invalid-Field-Sharing
 * 
 * Exceptions:
 * - Key fields are implicitly shareable: fields in a @key directive, and fields that a lookup
 *   argument maps to (a key that "could be inferred from a lookup field's arguments MAY be
 *   omitted", spec section @key)
 * - External fields are allowed (they reference fields from other subgraphs)
 * - Types and fields marked @internal do not take part
 * - Overridden fields (one definition has @override) are exempt
 *
 * Root fields follow the same rule: a lookup that several source schemas define needs @shareable
 * (or @internal), like any other field.
 */
public final class InvalidFieldSharingRule implements ValidationRule {
    
    private static final String CODE = "INVALID_FIELD_SHARING";
    
    @Override
    public ValidationPhase phase() {
        return ValidationPhase.PRE_MERGE;
    }
    
    @Override
    public String name() {
        return "InvalidFieldSharingRule";
    }
    
    @Override
    public ValidationResult validate(List<Subgraph> subgraphs) {
        ValidationResult.Builder builder = ValidationResult.builder();
        
        // First, collect all key fields from all subgraphs
        Map<String, Set<String>> keyFieldsByType = collectKeyFields(subgraphs);
        
        // Collect all fields by type.field coordinate
        Map<String, List<FieldInfo>> fieldsByCoordinate = new HashMap<>();
        
        for (Subgraph subgraph : subgraphs) {
            collectFields(subgraph, fieldsByCoordinate);
        }
        
        // Check for invalid sharing
        for (Map.Entry<String, List<FieldInfo>> entry : fieldsByCoordinate.entrySet()) {
            List<FieldInfo> fields = entry.getValue();
            if (fields.size() > 1) {
                validateFieldSharing(entry.getKey(), fields, keyFieldsByType, builder);
            }
        }
        
        return builder.build();
    }
    
    /**
     * Collects all key fields from all subgraphs: @key fields and the fields lookup arguments map to.
     * Key fields are implicitly shareable.
     */
    private Map<String, Set<String>> collectKeyFields(List<Subgraph> subgraphs) {
        Map<String, Set<String>> keyFieldsByType = new HashMap<>();
        
        for (Subgraph subgraph : subgraphs) {
            GraphQLSchema schema = subgraph.schema();
            
            for (GraphQLNamedType type : schema.getAllTypesAsList()) {
                if (type instanceof GraphQLFieldsContainer container) {
                    collectLookupKeyFields(schema, container, keyFieldsByType);
                }
                if (type instanceof GraphQLObjectType objectType) {
                    // Check if type has @key directive
                    List<GraphQLAppliedDirective> keyDirectives = objectType.getAppliedDirectives(KEY);
                    for (GraphQLAppliedDirective keyDirective : keyDirectives) {
                        GraphQLAppliedDirectiveArgument fieldsArg = keyDirective.getArgument("fields");
                        if (fieldsArg != null && fieldsArg.getValue() != null) {
                            String keyFields = getStringValue(fieldsArg.getValue());
                            if (keyFields != null) {
                                // Parse key fields (can be composite like "userId orderId")
                                Set<String> fields = keyFieldsByType
                                    .computeIfAbsent(objectType.getName(), k -> new HashSet<>());
                                for (String field : keyFields.split("\\s+")) {
                                    fields.add(field.trim());
                                }
                            }
                        }
                    }
                }
            }
        }
        
        return keyFieldsByType;
    }
    
    /**
     * Adds the fields that the arguments of the container's lookup fields map to (the argument name,
     * or the first field of its @is selection) as keys of the lookup's return type; for an abstract
     * return type, of every possible object type.
     */
    private void collectLookupKeyFields(GraphQLSchema schema, GraphQLFieldsContainer container,
                                        Map<String, Set<String>> keyFieldsByType) {
        for (GraphQLFieldDefinition field : container.getFieldDefinitions()) {
            if (!field.hasAppliedDirective(LOOKUP)) {
                continue;
            }
            GraphQLNamedType returnType = GraphQLTypeUtil.unwrapAllAs(field.getType());
            List<String> entityTypes = new ArrayList<>();
            if (returnType instanceof GraphQLObjectType objectType) {
                entityTypes.add(objectType.getName());
            } else if (returnType instanceof GraphQLInterfaceType interfaceType) {
                schema.getImplementations(interfaceType).forEach(t -> entityTypes.add(t.getName()));
            } else if (returnType instanceof GraphQLUnionType unionType) {
                unionType.getTypes().forEach(t -> entityTypes.add(t.getName()));
            }
            for (GraphQLArgument argument : field.getArguments()) {
                String keyField = lookupArgumentField(argument);
                for (String entityType : entityTypes) {
                    keyFieldsByType.computeIfAbsent(entityType, k -> new HashSet<>()).add(keyField);
                }
            }
        }
    }

    private String lookupArgumentField(GraphQLArgument argument) {
        GraphQLAppliedDirective is = argument.getAppliedDirective(IS);
        GraphQLAppliedDirectiveArgument fieldArgument = is == null ? null : is.getArgument("field");
        String selection = fieldArgument == null ? null : getStringValue(fieldArgument.getValue());
        if (selection == null) {
            return argument.getName();
        }
        // First field of the selection: "id", "product.id", "{ id sku }", "<Book>.isbn"
        String first = selection.replaceAll("<[^>]*>\\s*\\.?", " ").replaceAll("[{}.:,|\\[\\]]", " ").trim();
        return first.isEmpty() ? argument.getName() : first.split("\\s+")[0];
    }

    /**
     * Extracts string value from a GraphQL value (handles StringValue).
     */
    private String getStringValue(Object value) {
        if (value instanceof graphql.language.StringValue stringValue) {
            return stringValue.getValue();
        }
        if (value instanceof String s) {
            return s;
        }
        return null;
    }
    
    private void collectFields(Subgraph subgraph, Map<String, List<FieldInfo>> fieldsByCoordinate) {
        GraphQLSchema schema = subgraph.schema();
        
        for (GraphQLNamedType type : schema.getAllTypesAsList()) {
            if (type instanceof GraphQLObjectType objectType && !isBuiltInType(objectType.getName())
                && !objectType.hasAppliedDirective(INTERNAL)) {
                // Check if the type itself is marked as @external
                boolean typeIsExternal = objectType.hasAppliedDirective(EXTERNAL);
                
                for (GraphQLFieldDefinition field : objectType.getFieldDefinitions()) {
                    // Skip @internal fields - they don't participate in schema merging
                    if (field.hasAppliedDirective(INTERNAL)) {
                        continue;
                    }
                    String coordinate = objectType.getName() + "." + field.getName();
                    fieldsByCoordinate
                        .computeIfAbsent(coordinate, k -> new ArrayList<>())
                        .add(new FieldInfo(subgraph.name(), objectType.getName(), field, typeIsExternal));
                }
            }
        }
    }
    
    private void validateFieldSharing(String coordinate, List<FieldInfo> fields, 
            Map<String, Set<String>> keyFieldsByType, ValidationResult.Builder builder) {
        
        // Extract type and field name from coordinate
        int dotIndex = coordinate.lastIndexOf('.');
        String typeName = coordinate.substring(0, dotIndex);
        String fieldName = coordinate.substring(dotIndex + 1);
        
        // Key fields are implicitly shareable
        Set<String> keyFields = keyFieldsByType.get(typeName);
        if (keyFields != null && keyFields.contains(fieldName)) {
            return; // Key fields are implicitly shareable
        }
        
        // Overridden fields are exempt (spec: field names "part of a @key directive, or overridden")
        if (fields.stream().anyMatch(f -> f.field().hasAppliedDirective(OVERRIDE))) {
            return;
        }

        // Count non-external definitions - only flag if multiple non-external instances exist
        List<FieldInfo> nonExternalFields = fields.stream()
            .filter(f -> !f.field().hasAppliedDirective(EXTERNAL) && !f.typeIsExternal())
            .toList();
        
        // If only one non-external definition exists, no sharing problem
        if (nonExternalFields.size() <= 1) {
            return;
        }
        
        // Multiple non-external definitions - check if they're all shareable
        for (FieldInfo fieldInfo : nonExternalFields) {
            GraphQLFieldDefinition field = fieldInfo.field();
            boolean isShareable = field.hasAppliedDirective(SHAREABLE);
            
            if (!isShareable) {
                // This field is not shareable but is defined in multiple subgraphs
                String schemas = nonExternalFields.stream()
                    .map(FieldInfo::schemaName)
                    .toList()
                    .toString();
                    
                String message = String.format(
                    "Field '%s' is defined in multiple subgraphs %s but is not marked as @shareable in schema '%s'.",
                    coordinate, schemas, fieldInfo.schemaName()
                );
                builder.addError(CODE, message, coordinate, fieldInfo.schemaName(), SHAREABLE);
            }
        }
    }
    
    private boolean isBuiltInType(String typeName) {
        return Introspection.isIntrospectionTypes(typeName) || ScalarInfo.isGraphqlSpecifiedScalar(typeName);
    }
    
    private record FieldInfo(String schemaName, String typeName, GraphQLFieldDefinition field, boolean typeIsExternal) {}
}
