package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.parser.FieldSelectionMap.FieldSelection;
import dev.feddi.federation.engine.parser.FieldSelectionMap.FieldSelectionSet;
import dev.feddi.federation.engine.parser.FieldSelectionMap.InlineFragment;
import dev.feddi.federation.engine.parser.FieldSelectionMap.SelectionItem;
import dev.feddi.federation.engine.parser.FieldSelectionMapParser;
import dev.feddi.federation.engine.parser.InvalidSyntaxException;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import graphql.introspection.Introspection;
import graphql.language.StringValue;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLAppliedDirectiveArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.idl.ScalarInfo;

import java.util.ArrayList;
import java.util.List;

import static dev.feddi.federation.engine.compose.FederationDirectives.EXTERNAL;
import static dev.feddi.federation.engine.compose.FederationDirectives.PROVIDES;

/**
 * Validates that every @external field is referenced by a @provides directive.
 *
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-External-Unused
 *
 * "Every field marked as @external in a source schema is actually used by that source
 * schema in a @provides directive."
 */
public final class ExternalUnusedRule implements ValidationRule {

    private static final String CODE = "EXTERNAL_UNUSED";

    @Override
    public ValidationPhase phase() {
        return ValidationPhase.SOURCE_SCHEMA;
    }

    @Override
    public String name() {
        return "ExternalUnusedRule";
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
        String schemaName = subgraph.name();

        // Collect all @provides directives with their field selections and target types
        List<ProvidesInfo> providesList = collectProvides(schema);

        // Check each @external field
        for (GraphQLNamedType type : schema.getAllTypesAsList()) {
            if (type instanceof GraphQLObjectType objectType && !isBuiltInType(objectType.getName())) {
                for (GraphQLFieldDefinition field : objectType.getFieldDefinitions()) {
                    if (field.hasAppliedDirective(EXTERNAL)) {
                        if (!isFieldProvided(objectType.getName(), field.getName(), providesList, schema)) {
                            String coordinate = objectType.getName() + "." + field.getName();
                            builder.addError(CODE,
                                String.format("External field '%s' on type '%s' in schema '%s' is not referenced by any @provides directive.",
                                    field.getName(), objectType.getName(), schemaName),
                                coordinate, schemaName, EXTERNAL);
                        }
                    }
                }
            } else if (type instanceof GraphQLInterfaceType interfaceType && !isBuiltInType(interfaceType.getName())) {
                for (GraphQLFieldDefinition field : interfaceType.getFieldDefinitions()) {
                    if (field.hasAppliedDirective(EXTERNAL)) {
                        if (!isFieldProvided(interfaceType.getName(), field.getName(), providesList, schema)) {
                            String coordinate = interfaceType.getName() + "." + field.getName();
                            builder.addError(CODE,
                                String.format("External field '%s' on type '%s' in schema '%s' is not referenced by any @provides directive.",
                                    field.getName(), interfaceType.getName(), schemaName),
                                coordinate, schemaName, EXTERNAL);
                        }
                    }
                }
            }
        }
    }

    /**
     * Collects all @provides directives in the schema.
     */
    private List<ProvidesInfo> collectProvides(GraphQLSchema schema) {
        List<ProvidesInfo> provides = new ArrayList<>();

        for (GraphQLNamedType type : schema.getAllTypesAsList()) {
            if (type instanceof GraphQLObjectType objectType && !isBuiltInType(objectType.getName())) {
                for (GraphQLFieldDefinition field : objectType.getFieldDefinitions()) {
                    if (field.hasAppliedDirective(PROVIDES)) {
                        GraphQLAppliedDirective providesDirective = field.getAppliedDirective(PROVIDES);
                        GraphQLAppliedDirectiveArgument fieldsArg = providesDirective.getArgument("fields");
                        String fieldsStr = getStringValue(fieldsArg);
                        if (fieldsStr != null) {
                            // Get the target type of this field (the type on which provides applies)
                            String targetTypeName = GraphQLTypeUtil.unwrapAll(field.getType()).getName();
                            if (targetTypeName != null) {
                                provides.add(new ProvidesInfo(targetTypeName, fieldsStr, schema));
                            }
                        }
                    }
                }
            } else if (type instanceof GraphQLInterfaceType interfaceType && !isBuiltInType(interfaceType.getName())) {
                for (GraphQLFieldDefinition field : interfaceType.getFieldDefinitions()) {
                    if (field.hasAppliedDirective(PROVIDES)) {
                        GraphQLAppliedDirective providesDirective = field.getAppliedDirective(PROVIDES);
                        GraphQLAppliedDirectiveArgument fieldsArg = providesDirective.getArgument("fields");
                        String fieldsStr = getStringValue(fieldsArg);
                        if (fieldsStr != null) {
                            String targetTypeName = GraphQLTypeUtil.unwrapAll(field.getType()).getName();
                            if (targetTypeName != null) {
                                provides.add(new ProvidesInfo(targetTypeName, fieldsStr, schema));
                            }
                        }
                    }
                }
            }
        }

        return provides;
    }

    /**
     * Checks if a field is referenced by any @provides directive.
     */
    private boolean isFieldProvided(String typeName, String fieldName, List<ProvidesInfo> providesList, GraphQLSchema schema) {
        for (ProvidesInfo provides : providesList) {
            if (isFieldInProvides(typeName, fieldName, provides, schema)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a specific field is referenced in a @provides selection.
     * Handles nested selections like "variation { size }" and inline fragments like "... on Book { title }".
     *
     * Note: FieldSelectionMapSyntaxRule should validate syntax before this rule runs.
     * If parsing fails here, we skip this @provides entry (the syntax error will already be reported).
     */
    private boolean isFieldInProvides(String targetTypeName, String targetFieldName,
                                       ProvidesInfo provides, GraphQLSchema schema) {
        try {
            FieldSelectionSet selectionSet = FieldSelectionMapParser.parseFieldSelectionSet(provides.fieldsStr());
            return checkSelectionsForField(targetTypeName, targetFieldName, provides.targetTypeName(), selectionSet.items(), schema);
        } catch (InvalidSyntaxException e) {
            // Syntax error already reported by FieldSelectionMapSyntaxRule - skip this entry
            return false;
        }
    }

    /**
     * Recursively checks if a field is referenced in a list of selection items.
     */
    private boolean checkSelectionsForField(String targetTypeName, String targetFieldName,
                                             String currentTypeName, List<SelectionItem> items, GraphQLSchema schema) {
        for (SelectionItem item : items) {
            if (checkSelectionItemForField(targetTypeName, targetFieldName, currentTypeName, item, schema)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a single selection item references the target field.
     */
    private boolean checkSelectionItemForField(String targetTypeName, String targetFieldName,
                                                String currentTypeName, SelectionItem item, GraphQLSchema schema) {
        if (item instanceof FieldSelection fieldSelection) {
            return checkFieldSelectionForField(targetTypeName, targetFieldName, currentTypeName, fieldSelection, schema);
        } else if (item instanceof InlineFragment inlineFragment) {
            return checkInlineFragmentForField(targetTypeName, targetFieldName, inlineFragment, schema);
        }
        return false;
    }

    /**
     * Checks if a field selection references the target field.
     */
    private boolean checkFieldSelectionForField(String targetTypeName, String targetFieldName,
                                                 String currentTypeName, FieldSelection selection, GraphQLSchema schema) {
        // Check if this selection matches the target field on the target type
        // Also check if currentTypeName is an interface that targetTypeName implements
        if (selection.fieldName().equals(targetFieldName) && isTypeMatch(currentTypeName, targetTypeName, schema)) {
            return true;
        }

        // If this selection has sub-selections, recurse into them
        if (selection.hasSubSelections()) {
            // Get the type of this field to use as context for sub-selections
            String fieldType = getFieldType(currentTypeName, selection.fieldName(), schema);
            if (fieldType != null) {
                return checkSelectionsForField(targetTypeName, targetFieldName, fieldType, selection.subSelections(), schema);
            }
        }

        return false;
    }

    /**
     * Checks if an inline fragment references the target field.
     * Inline fragments have the form: ... on TypeName { selections }
     */
    private boolean checkInlineFragmentForField(String targetTypeName, String targetFieldName,
                                                 InlineFragment fragment, GraphQLSchema schema) {
        // The type condition of the inline fragment becomes the current type for the nested selections
        String fragmentTypeName = fragment.typeName();
        return checkSelectionsForField(targetTypeName, targetFieldName, fragmentTypeName, fragment.selections(), schema);
    }

    /**
     * Checks if the currentTypeName matches or is an interface/union that targetTypeName implements/is a member of.
     */
    private boolean isTypeMatch(String currentTypeName, String targetTypeName, GraphQLSchema schema) {
        if (currentTypeName.equals(targetTypeName)) {
            return true;
        }

        // Check if currentTypeName is an interface that targetTypeName implements
        GraphQLType currentType = schema.getType(currentTypeName);
        GraphQLType targetType = schema.getType(targetTypeName);

        if (currentType instanceof GraphQLInterfaceType interfaceType && targetType instanceof GraphQLObjectType objectType) {
            return objectType.getInterfaces().stream()
                .anyMatch(iface -> iface.getName().equals(currentTypeName));
        }

        return false;
    }

    /**
     * Gets the unwrapped type name of a field on a type.
     */
    private String getFieldType(String typeName, String fieldName, GraphQLSchema schema) {
        GraphQLType type = schema.getType(typeName);
        if (type instanceof GraphQLObjectType objectType) {
            GraphQLFieldDefinition field = objectType.getFieldDefinition(fieldName);
            if (field != null) {
                return GraphQLTypeUtil.unwrapAll(field.getType()).getName();
            }
        } else if (type instanceof GraphQLInterfaceType interfaceType) {
            GraphQLFieldDefinition field = interfaceType.getFieldDefinition(fieldName);
            if (field != null) {
                return GraphQLTypeUtil.unwrapAll(field.getType()).getName();
            }
        }
        return null;
    }

    private String getStringValue(GraphQLAppliedDirectiveArgument arg) {
        if (arg == null) return null;
        Object value = arg.getValue();
        if (value instanceof StringValue stringValue) {
            return stringValue.getValue();
        }
        return value != null ? value.toString() : null;
    }

    private boolean isBuiltInType(String typeName) {
        return Introspection.isIntrospectionTypes(typeName) || ScalarInfo.isGraphqlSpecifiedScalar(typeName);
    }

    /**
     * Information about a @provides directive.
     */
    private record ProvidesInfo(String targetTypeName, String fieldsStr, GraphQLSchema schema) {}
}
