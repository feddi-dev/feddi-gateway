package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.parser.FieldSelectionMap.Alternative;
import dev.feddi.federation.engine.parser.FieldSelectionMap.ListSelection;
import dev.feddi.federation.engine.parser.FieldSelectionMap.ObjectField;
import dev.feddi.federation.engine.parser.FieldSelectionMap.ObjectSelection;
import dev.feddi.federation.engine.parser.FieldSelectionMap.Path;
import dev.feddi.federation.engine.parser.FieldSelectionMap.PathSegment;
import dev.feddi.federation.engine.parser.FieldSelectionMap.SelectedValue;
import dev.feddi.federation.engine.parser.FieldSelectionMapParser;
import dev.feddi.federation.engine.compose.validation.CrossSchemaFieldResolver;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import graphql.language.StringValue;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLAppliedDirectiveArgument;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.feddi.federation.engine.compose.FederationDirectives.IS;

/**
 * Validates that @is directive field references exist in OTHER schemas.
 *
 * The @is directive maps an argument value to a field on the return type.
 * The referenced fields must be resolvable from the combined context of
 * all OTHER schemas (not the schema defining the @is directive).
 *
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Is-Invalid-Fields
 */
public final class IsInvalidFieldsRule implements ValidationRule {

    private static final String CODE = "IS_INVALID_FIELDS";

    @Override
    public ValidationPhase phase() {
        return ValidationPhase.PRE_MERGE;
    }

    @Override
    public String name() {
        return "IsInvalidFieldsRule";
    }

    @Override
    public ValidationResult validate(List<Subgraph> subgraphs) {
        ValidationResult.Builder builder = ValidationResult.builder();

        for (Subgraph subgraph : subgraphs) {
            validateSubgraph(subgraph, subgraphs, builder);
        }

        return builder.build();
    }

    private void validateSubgraph(Subgraph subgraph, List<Subgraph> allSubgraphs,
                                  ValidationResult.Builder builder) {
        String schemaName = subgraph.name();

        // Build cross-schema field maps (excluding current schema)
        Map<String, Set<String>> crossSchemaFields =
            CrossSchemaFieldResolver.buildCrossSchemaFieldMap(allSubgraphs, schemaName);
        Map<String, Map<String, String>> crossSchemaFieldTypes =
            CrossSchemaFieldResolver.buildCrossSchemaFieldTypeMap(allSubgraphs, schemaName);

        for (GraphQLType type : subgraph.schema().getAllTypesAsList()) {
            if (type instanceof GraphQLObjectType objectType) {
                validateObjectType(objectType, schemaName, crossSchemaFields,
                    crossSchemaFieldTypes, allSubgraphs, builder);
            } else if (type instanceof GraphQLInterfaceType interfaceType) {
                validateInterfaceType(interfaceType, schemaName, crossSchemaFields,
                    crossSchemaFieldTypes, allSubgraphs, builder);
            }
        }
    }

    private void validateObjectType(GraphQLObjectType type, String schemaName,
                                    Map<String, Set<String>> crossSchemaFields,
                                    Map<String, Map<String, String>> crossSchemaFieldTypes,
                                    List<Subgraph> allSubgraphs,
                                    ValidationResult.Builder builder) {
        for (GraphQLFieldDefinition field : type.getFieldDefinitions()) {
            // @is is typically on @lookup field arguments
            validateFieldArguments(type.getName(), field, schemaName,
                crossSchemaFields, crossSchemaFieldTypes, allSubgraphs, builder);
        }
    }

    private void validateInterfaceType(GraphQLInterfaceType type, String schemaName,
                                       Map<String, Set<String>> crossSchemaFields,
                                       Map<String, Map<String, String>> crossSchemaFieldTypes,
                                       List<Subgraph> allSubgraphs,
                                       ValidationResult.Builder builder) {
        for (GraphQLFieldDefinition field : type.getFieldDefinitions()) {
            validateFieldArguments(type.getName(), field, schemaName,
                crossSchemaFields, crossSchemaFieldTypes, allSubgraphs, builder);
        }
    }

    private void validateFieldArguments(String typeName, GraphQLFieldDefinition field,
                                        String schemaName,
                                        Map<String, Set<String>> crossSchemaFields,
                                        Map<String, Map<String, String>> crossSchemaFieldTypes,
                                        List<Subgraph> allSubgraphs,
                                        ValidationResult.Builder builder) {
        // Get the return type of the field - this is where @is fields are resolved
        GraphQLType returnType = GraphQLTypeUtil.unwrapAll(field.getType());
        if (!(returnType instanceof GraphQLNamedType namedReturnType)) {
            return;
        }
        String declaringTypeName = namedReturnType.getName();

        for (GraphQLArgument arg : field.getArguments()) {
            if (!arg.hasAppliedDirective(IS)) {
                continue;
            }

            GraphQLAppliedDirective isDirective = arg.getAppliedDirective(IS);
            GraphQLAppliedDirectiveArgument fieldArg = isDirective.getArgument("field");
            if (fieldArg == null) {
                continue;
            }

            String fieldString = extractStringValue(fieldArg.getValue());
            if (fieldString == null || fieldString.isBlank()) {
                continue; // Type validation is done by another rule
            }

            String coordinate = typeName + "." + field.getName() + "(" + arg.getName() + ")";

            try {
                SelectedValue selectedValue = FieldSelectionMapParser.parseFieldSelectionMap(fieldString);
                validateSelectedValue(selectedValue, declaringTypeName, coordinate, schemaName,
                    crossSchemaFields, crossSchemaFieldTypes, allSubgraphs, builder);
            } catch (Exception e) {
                // Syntax errors are handled by FieldSelectionMapSyntaxRule
            }
        }
    }

    private void validateSelectedValue(SelectedValue selectedValue, String currentTypeName,
                                       String coordinate, String schemaName,
                                       Map<String, Set<String>> crossSchemaFields,
                                       Map<String, Map<String, String>> crossSchemaFieldTypes,
                                       List<Subgraph> allSubgraphs,
                                       ValidationResult.Builder builder) {
        for (Alternative alt : selectedValue.alternatives()) {
            validateAlternative(alt, currentTypeName, coordinate, schemaName,
                crossSchemaFields, crossSchemaFieldTypes, allSubgraphs, builder);
        }
    }

    private void validateAlternative(Alternative alt, String currentTypeName,
                                     String coordinate, String schemaName,
                                     Map<String, Set<String>> crossSchemaFields,
                                     Map<String, Map<String, String>> crossSchemaFieldTypes,
                                     List<Subgraph> allSubgraphs,
                                     ValidationResult.Builder builder) {
        if (alt instanceof Path path) {
            validatePath(path, currentTypeName, coordinate, schemaName,
                crossSchemaFields, crossSchemaFieldTypes, allSubgraphs, builder);
        } else if (alt instanceof ObjectSelection objectSelection) {
            validateObjectSelection(objectSelection, currentTypeName, coordinate, schemaName,
                crossSchemaFields, crossSchemaFieldTypes, allSubgraphs, builder);
        } else if (alt instanceof ListSelection listSelection) {
            validateListSelection(listSelection, currentTypeName, coordinate, schemaName,
                crossSchemaFields, crossSchemaFieldTypes, allSubgraphs, builder);
        }
    }

    private void validatePath(Path path, String currentTypeName,
                              String coordinate, String schemaName,
                              Map<String, Set<String>> crossSchemaFields,
                              Map<String, Map<String, String>> crossSchemaFieldTypes,
                              List<Subgraph> allSubgraphs,
                              ValidationResult.Builder builder) {
        String typeName = currentTypeName;

        // Handle initial type condition (e.g., <Movie> in "<Movie>.imdbCode")
        // This sets the lookup context BEFORE the first field
        if (path.hasInitialTypeCondition()) {
            typeName = path.initialTypeCondition();
            // Validate type exists
            if (!CrossSchemaFieldResolver.typeExistsInAnySchema(allSubgraphs, typeName)) {
                String message = String.format(
                    "@is directive at '%s' in schema '%s' references non-existent type '%s'.",
                    coordinate, schemaName, typeName
                );
                builder.addError(CODE, message, coordinate, schemaName);
                return;
            }
        }

        for (PathSegment segment : path.segments()) {
            String fieldName = segment.fieldName();

            // Check if field exists in ANY schema (including current one)
            // @is maps arguments to fields on the return type, which can be from any schema
            if (!CrossSchemaFieldResolver.isFieldInAnySchema(allSubgraphs, typeName, fieldName)) {
                String message = String.format(
                    "@is directive at '%s' in schema '%s' references field '%s' on type '%s' " +
                    "which does not exist in any schema.",
                    coordinate, schemaName, fieldName, typeName
                );
                builder.addError(CODE, message, coordinate, schemaName);
                return;
            }

            // Update type for next segment:
            // - If segment has infix type condition (e.g., field<Book>), use that type (return type narrowing)
            // - Otherwise, use the field's return type
            if (segment.hasTypeCondition()) {
                typeName = segment.typeCondition();
                // Validate the narrowed type exists
                if (!CrossSchemaFieldResolver.typeExistsInAnySchema(allSubgraphs, typeName)) {
                    String message = String.format(
                        "@is directive at '%s' in schema '%s' references non-existent type '%s'.",
                        coordinate, schemaName, typeName
                    );
                    builder.addError(CODE, message, coordinate, schemaName);
                    return;
                }
            } else {
                String nextType = CrossSchemaFieldResolver.getFieldReturnTypeFromAnySchema(
                    allSubgraphs, typeName, fieldName);
                if (nextType != null) {
                    typeName = nextType;
                }
            }
        }
    }

    private void validateObjectSelection(ObjectSelection objectSelection, String currentTypeName,
                                         String coordinate, String schemaName,
                                         Map<String, Set<String>> crossSchemaFields,
                                         Map<String, Map<String, String>> crossSchemaFieldTypes,
                                         List<Subgraph> allSubgraphs,
                                         ValidationResult.Builder builder) {
        String typeName = currentTypeName;

        // First validate path prefix if present
        if (objectSelection.pathPrefix() != null) {
            validatePath(objectSelection.pathPrefix(), currentTypeName, coordinate, schemaName,
                crossSchemaFields, crossSchemaFieldTypes, allSubgraphs, builder);

            // Update type context based on path
            typeName = getTypeAfterPath(objectSelection.pathPrefix(), currentTypeName, allSubgraphs);
        }

        // Validate object fields - the field.name() is an alias (mapping key for input),
        // the field.value() contains the actual path being required from the schema
        for (ObjectField field : objectSelection.fields()) {
            // Only validate the value (path) - the name is just an alias for mapping
            if (field.value() != null) {
                validateSelectedValue(field.value(), typeName, coordinate, schemaName,
                    crossSchemaFields, crossSchemaFieldTypes, allSubgraphs, builder);
            }
        }
    }

    private void validateListSelection(ListSelection listSelection, String currentTypeName,
                                       String coordinate, String schemaName,
                                       Map<String, Set<String>> crossSchemaFields,
                                       Map<String, Map<String, String>> crossSchemaFieldTypes,
                                       List<Subgraph> allSubgraphs,
                                       ValidationResult.Builder builder) {
        String typeName = currentTypeName;

        // First validate path prefix if present
        if (listSelection.pathPrefix() != null) {
            validatePath(listSelection.pathPrefix(), currentTypeName, coordinate, schemaName,
                crossSchemaFields, crossSchemaFieldTypes, allSubgraphs, builder);

            // Update type context based on path
            typeName = getTypeAfterPath(listSelection.pathPrefix(), currentTypeName, allSubgraphs);
        }

        // Validate element value
        if (listSelection.elementValue() != null) {
            validateSelectedValue(listSelection.elementValue(), typeName, coordinate, schemaName,
                crossSchemaFields, crossSchemaFieldTypes, allSubgraphs, builder);
        }
    }

    /**
     * Computes the type context after traversing a path.
     * Handles both initial type conditions and infix type conditions.
     */
    private String getTypeAfterPath(Path path, String startingType, List<Subgraph> allSubgraphs) {
        String typeName = startingType;

        // Handle initial type condition
        if (path.hasInitialTypeCondition()) {
            typeName = path.initialTypeCondition();
        }

        // Traverse segments
        for (PathSegment segment : path.segments()) {
            // If segment has infix type condition, use it
            if (segment.hasTypeCondition()) {
                typeName = segment.typeCondition();
            } else {
                // Otherwise, use field's return type
                String nextType = CrossSchemaFieldResolver.getFieldReturnTypeFromAnySchema(
                    allSubgraphs, typeName, segment.fieldName());
                if (nextType != null) {
                    typeName = nextType;
                }
            }
        }

        return typeName;
    }

    /**
     * Extracts a string value from a directive argument.
     * Handles both raw String and StringValue (GraphQL AST) types.
     */
    private String extractStringValue(Object value) {
        if (value instanceof StringValue stringValue) {
            return stringValue.getValue();
        }
        if (value instanceof String str) {
            return str;
        }
        return value != null ? value.toString() : null;
    }
}
