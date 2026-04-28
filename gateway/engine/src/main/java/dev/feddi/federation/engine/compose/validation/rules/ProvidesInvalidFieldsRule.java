package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.parser.FieldSelectionMap.FieldSelection;
import dev.feddi.federation.engine.parser.FieldSelectionMap.FieldSelectionSet;
import dev.feddi.federation.engine.parser.FieldSelectionMap.InlineFragment;
import dev.feddi.federation.engine.parser.FieldSelectionMap.SelectionItem;
import dev.feddi.federation.engine.parser.FieldSelectionMapParser;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLAppliedDirectiveArgument;
import graphql.schema.GraphQLDirectiveContainer;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnionType;

import java.util.List;

import static dev.feddi.federation.engine.compose.FederationDirectives.EXTERNAL;
import static dev.feddi.federation.engine.compose.FederationDirectives.KEY;
import static dev.feddi.federation.engine.compose.FederationDirectives.PROVIDES;

/**
 * Validates that @provides fields reference existing fields on the return type.
 * Also validates that:
 * - PROVIDES_INVALID_FIELDS: fields must exist on the return type
 * - PROVIDES_INVALID_FIELDS_TYPE: fields argument must be a string
 * - PROVIDES_ON_NON_COMPOSITE_FIELD: @provides only on fields returning composite types
 * - PROVIDES_FIELDS_MISSING_EXTERNAL: referenced fields must be @external
 * - PROVIDES_FIELDS_HAS_ARGUMENTS: referenced fields cannot have arguments
 *
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Provides-Invalid-Fields
 */
public final class ProvidesInvalidFieldsRule implements ValidationRule {

    private static final String PROVIDES_INVALID_FIELDS = "PROVIDES_INVALID_FIELDS";
    private static final String PROVIDES_INVALID_FIELDS_TYPE = "PROVIDES_INVALID_FIELDS_TYPE";
    private static final String PROVIDES_ON_NON_COMPOSITE_FIELD = "PROVIDES_ON_NON_COMPOSITE_FIELD";
    private static final String PROVIDES_FIELDS_HAS_ARGUMENTS = "PROVIDES_FIELDS_HAS_ARGUMENTS";
    private static final String PROVIDES_FIELDS_MISSING_EXTERNAL = "PROVIDES_FIELDS_MISSING_EXTERNAL";

    @Override
    public ValidationPhase phase() {
        return ValidationPhase.SOURCE_SCHEMA;
    }

    @Override
    public String name() {
        return "ProvidesInvalidFieldsRule";
    }

    @Override
    public ValidationResult validate(List<Subgraph> subgraphs) {
        ValidationResult.Builder builder = ValidationResult.builder();

        for (Subgraph subgraph : subgraphs) {
            validateSubgraph(subgraph, builder);
        }

        return builder.build();
    }

    private void validateSubgraph(Subgraph subgraph, ValidationResult.Builder builder) {
        GraphQLSchema schema = subgraph.schema();

        for (GraphQLType type : schema.getAllTypesAsList()) {
            if (type instanceof GraphQLObjectType objType) {
                validateType(objType, subgraph.name(), schema, builder);
            } else if (type instanceof GraphQLInterfaceType ifaceType) {
                validateType(ifaceType, subgraph.name(), schema, builder);
            }
        }
    }

    private void validateType(GraphQLFieldsContainer type, String schemaName, GraphQLSchema schema,
                              ValidationResult.Builder builder) {
        String typeName = ((GraphQLNamedType) type).getName();

        for (GraphQLFieldDefinition field : type.getFieldDefinitions()) {
            if (!(field instanceof GraphQLDirectiveContainer container)) {
                continue;
            }

            for (GraphQLAppliedDirective providesDirective : container.getAppliedDirectives(PROVIDES)) {
                GraphQLAppliedDirectiveArgument fieldsArg = providesDirective.getArgument("fields");
                if (fieldsArg == null) {
                    continue;
                }

                Object fieldsValue = fieldsArg.getValue();
                String fieldCoordinate = typeName + "." + field.getName();

                // PROVIDES_INVALID_FIELDS_TYPE: fields must be a string
                if (!(fieldsValue instanceof String fieldsString)) {
                    String message = String.format(
                        "The @provides directive on field '%s' in schema '%s' has a non-string 'fields' argument.",
                        fieldCoordinate, schemaName
                    );
                    builder.addError(PROVIDES_INVALID_FIELDS_TYPE, message, fieldCoordinate, schemaName);
                    continue;
                }

                // PROVIDES_ON_NON_COMPOSITE_FIELD: check return type is composite (object, interface, or union)
                GraphQLType returnType = GraphQLTypeUtil.unwrapAll(field.getType());
                if (!(returnType instanceof GraphQLObjectType) &&
                    !(returnType instanceof GraphQLInterfaceType) &&
                    !(returnType instanceof GraphQLUnionType)) {
                    String message = String.format(
                        "The @provides directive on field '%s' in schema '%s' returns non-composite type '%s'. @provides can only be used on fields returning object, interface, or union types.",
                        fieldCoordinate, schemaName, GraphQLTypeUtil.simplePrint(returnType)
                    );
                    builder.addError(PROVIDES_ON_NON_COMPOSITE_FIELD, message, fieldCoordinate, schemaName);
                    continue;
                }

                // Parse and validate the fields selection
                try {
                    FieldSelectionSet selectionSet = FieldSelectionMapParser.parseFieldSelectionSet(fieldsString);
                    if (returnType instanceof GraphQLFieldsContainer fieldsContainer) {
                        validateFieldSelection(selectionSet, fieldsContainer, fieldCoordinate,
                            schemaName, schema, builder);
                    } else if (returnType instanceof GraphQLUnionType) {
                        // For unions, only inline fragments are valid - fields must be validated within fragments
                        validateUnionFieldSelection(selectionSet, fieldCoordinate, schemaName, schema, builder);
                    }
                } catch (Exception e) {
                    // Syntax errors are handled by FieldSelectionMapSyntaxRule
                }
            }
        }
    }

    private void validateFieldSelection(FieldSelectionSet selectionSet, GraphQLFieldsContainer parentType,
                                        String provideFieldCoordinate, String schemaName, GraphQLSchema schema,
                                        ValidationResult.Builder builder) {
        for (SelectionItem item : selectionSet.items()) {
            if (item instanceof FieldSelection field) {
                validateField(field, parentType, provideFieldCoordinate, schemaName, schema, builder);
            } else if (item instanceof InlineFragment fragment) {
                validateInlineFragment(fragment, provideFieldCoordinate, schemaName, schema, builder);
            }
        }
    }

    private void validateField(FieldSelection field, GraphQLFieldsContainer parentType,
                               String provideFieldCoordinate, String schemaName, GraphQLSchema schema,
                               ValidationResult.Builder builder) {
        String fieldName = field.fieldName();
        String parentTypeName = ((GraphQLNamedType) parentType).getName();
        String fieldCoordinate = parentTypeName + "." + fieldName;

        // PROVIDES_INVALID_FIELDS: field must exist
        GraphQLFieldDefinition fieldDef = parentType.getFieldDefinition(fieldName);
        if (fieldDef == null) {
            String message = String.format(
                "The @provides directive on field '%s' in schema '%s' references non-existent field '%s'.",
                provideFieldCoordinate, schemaName, fieldCoordinate
            );
            builder.addError(PROVIDES_INVALID_FIELDS, message, provideFieldCoordinate, schemaName);
            return;
        }

        // PROVIDES_FIELDS_HAS_ARGUMENTS: field cannot have arguments defined
        if (!fieldDef.getArguments().isEmpty()) {
            String message = String.format(
                "The @provides directive on field '%s' in schema '%s' references field '%s' which has arguments. Provided fields cannot have arguments.",
                provideFieldCoordinate, schemaName, fieldCoordinate
            );
            builder.addError(PROVIDES_FIELDS_HAS_ARGUMENTS, message, provideFieldCoordinate, schemaName);
        }

        // PROVIDES_FIELDS_MISSING_EXTERNAL: field must be marked @external
        // Exceptions:
        // 1. Key fields (fields that are part of @key) don't need to be @external
        // 2. Interface fields - @external is typically on implementing types, not the interface itself
        //    Full validation would require cross-type analysis of all implementing types
        if (parentType instanceof GraphQLObjectType) {
            boolean isKeyField = isFieldInKey(parentType, fieldName);
            if (!isKeyField) {
                boolean isExternal = fieldDef.hasAppliedDirective(EXTERNAL);
                if (!isExternal) {
                    String message = String.format(
                        "The @provides directive on field '%s' in schema '%s' references field '%s' which is not marked @external. " +
                        "Fields in @provides must be @external fields that can be provided by this subgraph.",
                        provideFieldCoordinate, schemaName, fieldCoordinate
                    );
                    builder.addError(PROVIDES_FIELDS_MISSING_EXTERNAL, message, provideFieldCoordinate, schemaName);
                }
            }
        }
        // Note: For interface types, we skip this check since @external is typically on
        // implementing type fields, not on the interface definition itself.

        // Recursively validate nested selections for composite types
        GraphQLType fieldType = GraphQLTypeUtil.unwrapAll(fieldDef.getType());
        if (field.hasSubSelections() && fieldType instanceof GraphQLFieldsContainer nestedContainer) {
            for (SelectionItem subItem : field.subSelections()) {
                if (subItem instanceof FieldSelection subField) {
                    validateField(subField, nestedContainer, provideFieldCoordinate, schemaName, schema, builder);
                } else if (subItem instanceof InlineFragment fragment) {
                    validateInlineFragment(fragment, provideFieldCoordinate, schemaName, schema, builder);
                }
            }
        } else if (!field.hasSubSelections() && fieldType instanceof GraphQLFieldsContainer) {
            // PROVIDES_INVALID_FIELDS: composite types must have sub-selections
            String message = String.format(
                "The @provides directive on field '%s' in schema '%s' references field '%s' which returns a composite type but has no sub-selections.",
                provideFieldCoordinate, schemaName, fieldCoordinate
            );
            builder.addError(PROVIDES_INVALID_FIELDS, message, provideFieldCoordinate, schemaName);
        }
    }

    private void validateUnionFieldSelection(FieldSelectionSet selectionSet, String provideFieldCoordinate,
                                             String schemaName, GraphQLSchema schema, ValidationResult.Builder builder) {
        // For unions, only inline fragments are valid - direct field access is not allowed
        for (SelectionItem item : selectionSet.items()) {
            if (item instanceof FieldSelection field) {
                String message = String.format(
                    "The @provides directive on field '%s' in schema '%s' has a direct field selection '%s' on a union type. Use inline fragments to select fields.",
                    provideFieldCoordinate, schemaName, field.fieldName()
                );
                builder.addError(PROVIDES_INVALID_FIELDS, message, provideFieldCoordinate, schemaName);
            } else if (item instanceof InlineFragment fragment) {
                validateInlineFragment(fragment, provideFieldCoordinate, schemaName, schema, builder);
            }
        }
    }

    private void validateInlineFragment(InlineFragment fragment, String provideFieldCoordinate,
                                        String schemaName, GraphQLSchema schema, ValidationResult.Builder builder) {
        String typeName = fragment.typeName();
        GraphQLType type = schema.getType(typeName);

        if (type == null) {
            String message = String.format(
                "The @provides directive on field '%s' in schema '%s' references non-existent type '%s' in inline fragment.",
                provideFieldCoordinate, schemaName, typeName
            );
            builder.addError(PROVIDES_INVALID_FIELDS, message, provideFieldCoordinate, schemaName);
            return;
        }

        if (!(type instanceof GraphQLFieldsContainer fieldsContainer)) {
            return;
        }

        // Validate selections within the fragment
        for (SelectionItem item : fragment.selections()) {
            if (item instanceof FieldSelection field) {
                validateField(field, fieldsContainer, provideFieldCoordinate, schemaName, schema, builder);
            } else if (item instanceof InlineFragment nestedFragment) {
                validateInlineFragment(nestedFragment, provideFieldCoordinate, schemaName, schema, builder);
            }
        }
    }

    /**
     * Checks if a field is part of a @key directive on the given type.
     * Fields listed in @key are implicitly shareable and don't need to be @external.
     */
    private boolean isFieldInKey(GraphQLFieldsContainer type, String fieldName) {
        if (!(type instanceof GraphQLDirectiveContainer container)) {
            return false;
        }

        for (GraphQLAppliedDirective keyDirective : container.getAppliedDirectives(KEY)) {
            GraphQLAppliedDirectiveArgument fieldsArg = keyDirective.getArgument("fields");
            if (fieldsArg == null) {
                continue;
            }

            Object fieldsValue = fieldsArg.getValue();
            if (fieldsValue instanceof String fieldsString) {
                // Simple check: see if the field name appears as a top-level field in the key
                // This handles common cases like "id" or "id name"
                // For nested keys like "author { id }", only "author" is a top-level key field
                try {
                    FieldSelectionSet selectionSet = FieldSelectionMapParser.parseFieldSelectionSet(fieldsString);
                    for (SelectionItem item : selectionSet.items()) {
                        if (item instanceof FieldSelection keyField && keyField.fieldName().equals(fieldName)) {
                            return true;
                        }
                    }
                } catch (Exception e) {
                    // If parsing fails, fall back to simple string check
                    // This is a conservative approach - we check if the field name appears as a word
                    String[] parts = fieldsString.trim().split("\\s+|\\{|\\}");
                    for (String part : parts) {
                        if (part.equals(fieldName)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }
}
