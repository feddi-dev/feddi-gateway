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

import static dev.feddi.federation.engine.compose.FederationDirectives.KEY;

/**
 * Validates that @key fields reference existing fields on the type.
 * Also validates that:
 * - KEY_INVALID_FIELDS: fields must exist on the type
 * - KEY_INVALID_FIELDS_TYPE: fields argument must be a string
 * - KEY_FIELDS_SELECT_INVALID_TYPE: fields cannot be List, Interface, or Union types
 * - KEY_FIELDS_HAS_ARGUMENTS: fields cannot have arguments
 * - KEY_DIRECTIVE_IN_FIELDS_ARGUMENT: fields cannot contain directives (not currently parsed by FSM grammar)
 *
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Key-Invalid-Fields
 */
public final class KeyInvalidFieldsRule implements ValidationRule {

    private static final String KEY_INVALID_FIELDS = "KEY_INVALID_FIELDS";
    private static final String KEY_INVALID_FIELDS_TYPE = "KEY_INVALID_FIELDS_TYPE";
    private static final String KEY_FIELDS_SELECT_INVALID_TYPE = "KEY_FIELDS_SELECT_INVALID_TYPE";
    private static final String KEY_FIELDS_HAS_ARGUMENTS = "KEY_FIELDS_HAS_ARGUMENTS";

    @Override
    public ValidationPhase phase() {
        return ValidationPhase.SOURCE_SCHEMA;
    }

    @Override
    public String name() {
        return "KeyInvalidFieldsRule";
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

    private void validateType(GraphQLNamedType type, String schemaName, GraphQLSchema schema, ValidationResult.Builder builder) {
        if (!(type instanceof GraphQLDirectiveContainer container)) {
            return;
        }

        for (GraphQLAppliedDirective keyDirective : container.getAppliedDirectives(KEY)) {
            GraphQLAppliedDirectiveArgument fieldsArg = keyDirective.getArgument("fields");
            if (fieldsArg == null) {
                continue;
            }

            Object fieldsValue = fieldsArg.getValue();

            // KEY_INVALID_FIELDS_TYPE: fields must be a string
            if (!(fieldsValue instanceof String fieldsString)) {
                String message = String.format(
                    "The @key directive on type '%s' in schema '%s' has a non-string 'fields' argument.",
                    type.getName(), schemaName
                );
                builder.addError(KEY_INVALID_FIELDS_TYPE, message, type.getName(), schemaName);
                continue;
            }

            // Parse and validate the fields
            try {
                FieldSelectionSet selectionSet = FieldSelectionMapParser.parseFieldSelectionSet(fieldsString);
                validateFieldSelection(selectionSet, type, schemaName, schema, builder);
            } catch (Exception e) {
                // Syntax errors are handled by FieldSelectionMapSyntaxRule
            }
        }
    }

    private void validateFieldSelection(FieldSelectionSet selectionSet, GraphQLNamedType parentType,
                                        String schemaName, GraphQLSchema schema, ValidationResult.Builder builder) {
        GraphQLFieldsContainer fieldsContainer = null;
        if (parentType instanceof GraphQLObjectType obj) {
            fieldsContainer = obj;
        } else if (parentType instanceof GraphQLInterfaceType iface) {
            fieldsContainer = iface;
        }

        if (fieldsContainer == null) {
            return;
        }

        for (SelectionItem item : selectionSet.items()) {
            if (item instanceof FieldSelection field) {
                validateField(field, fieldsContainer, parentType, schemaName, schema, builder);
            } else if (item instanceof InlineFragment fragment) {
                // Inline fragments in @key must reference valid types
                validateInlineFragment(fragment, schemaName, schema, builder);
            }
        }
    }

    private void validateField(FieldSelection field, GraphQLFieldsContainer fieldsContainer,
                               GraphQLNamedType parentType, String schemaName, GraphQLSchema schema,
                               ValidationResult.Builder builder) {
        String fieldName = field.fieldName();

        // KEY_INVALID_FIELDS: field must exist
        GraphQLFieldDefinition fieldDef = fieldsContainer.getFieldDefinition(fieldName);
        if (fieldDef == null) {
            String message = String.format(
                "The @key directive on type '%s' in schema '%s' references non-existent field '%s'.",
                parentType.getName(), schemaName, fieldName
            );
            builder.addError(KEY_INVALID_FIELDS, message, parentType.getName() + "." + fieldName, schemaName);
            return;
        }

        // KEY_FIELDS_HAS_ARGUMENTS: field cannot have arguments defined
        if (!fieldDef.getArguments().isEmpty()) {
            String message = String.format(
                "The @key directive on type '%s' in schema '%s' references field '%s' which has arguments. Key fields cannot have arguments.",
                parentType.getName(), schemaName, fieldName
            );
            builder.addError(KEY_FIELDS_HAS_ARGUMENTS, message, parentType.getName() + "." + fieldName, schemaName);
        }

        // KEY_FIELDS_SELECT_INVALID_TYPE: check field type
        GraphQLType fieldType = GraphQLTypeUtil.unwrapAll(fieldDef.getType());

        if (GraphQLTypeUtil.isList(fieldDef.getType())) {
            String message = String.format(
                "The @key directive on type '%s' in schema '%s' references field '%s' which returns a List type. Key fields cannot be lists.",
                parentType.getName(), schemaName, fieldName
            );
            builder.addError(KEY_FIELDS_SELECT_INVALID_TYPE, message, parentType.getName() + "." + fieldName, schemaName);
        } else if (fieldType instanceof GraphQLInterfaceType) {
            String message = String.format(
                "The @key directive on type '%s' in schema '%s' references field '%s' which returns an Interface type. Key fields cannot be interfaces.",
                parentType.getName(), schemaName, fieldName
            );
            builder.addError(KEY_FIELDS_SELECT_INVALID_TYPE, message, parentType.getName() + "." + fieldName, schemaName);
        } else if (fieldType instanceof GraphQLUnionType) {
            String message = String.format(
                "The @key directive on type '%s' in schema '%s' references field '%s' which returns a Union type. Key fields cannot be unions.",
                parentType.getName(), schemaName, fieldName
            );
            builder.addError(KEY_FIELDS_SELECT_INVALID_TYPE, message, parentType.getName() + "." + fieldName, schemaName);
        }

        // Recursively validate nested selections
        if (field.hasSubSelections() && fieldType instanceof GraphQLFieldsContainer nestedContainer) {
            for (SelectionItem subItem : field.subSelections()) {
                if (subItem instanceof FieldSelection subField) {
                    validateField(subField, nestedContainer, (GraphQLNamedType) fieldType, schemaName, schema, builder);
                } else if (subItem instanceof InlineFragment fragment) {
                    validateInlineFragment(fragment, schemaName, schema, builder);
                }
            }
        }
    }

    private void validateInlineFragment(InlineFragment fragment, String schemaName,
                                        GraphQLSchema schema, ValidationResult.Builder builder) {
        String typeName = fragment.typeName();
        GraphQLType type = schema.getType(typeName);

        if (type == null) {
            String message = String.format(
                "The @key directive in schema '%s' references non-existent type '%s' in inline fragment.",
                schemaName, typeName
            );
            builder.addError(KEY_INVALID_FIELDS, message, typeName, schemaName);
            return;
        }

        GraphQLFieldsContainer fieldsContainer = null;
        if (type instanceof GraphQLObjectType obj) {
            fieldsContainer = obj;
        } else if (type instanceof GraphQLInterfaceType iface) {
            fieldsContainer = iface;
        }

        if (fieldsContainer == null) {
            return;
        }

        // Validate selections within the fragment
        for (SelectionItem item : fragment.selections()) {
            if (item instanceof FieldSelection field) {
                validateField(field, fieldsContainer, (GraphQLNamedType) type, schemaName, schema, builder);
            } else if (item instanceof InlineFragment nestedFragment) {
                validateInlineFragment(nestedFragment, schemaName, schema, builder);
            }
        }
    }
}
