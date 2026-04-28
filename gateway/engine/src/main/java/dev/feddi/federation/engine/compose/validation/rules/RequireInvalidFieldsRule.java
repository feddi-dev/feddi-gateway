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
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.feddi.federation.engine.compose.FederationDirectives.REQUIRE;

/**
 * Validates that @require directive field references exist in OTHER schemas (not the defining schema).
 *
 * The @require directive declares that a field argument depends on other fields
 * that must be fetched from OTHER schemas. If the required field exists only in
 * the same schema, or doesn't exist at all, this is an error.
 *
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Require-Invalid-Fields
 */
public final class RequireInvalidFieldsRule implements ValidationRule {

    private static final String CODE = "REQUIRE_INVALID_FIELDS";

    @Override
    public ValidationPhase phase() {
        return ValidationPhase.PRE_MERGE;
    }

    @Override
    public String name() {
        return "RequireInvalidFieldsRule";
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
                validateObjectType(objectType, subgraph, schemaName, crossSchemaFields,
                    crossSchemaFieldTypes, allSubgraphs, builder);
            } else if (type instanceof GraphQLInterfaceType interfaceType) {
                validateInterfaceType(interfaceType, subgraph, schemaName, crossSchemaFields,
                    crossSchemaFieldTypes, allSubgraphs, builder);
            }
        }
    }

    private void validateObjectType(GraphQLObjectType type, Subgraph currentSubgraph,
                                    String schemaName,
                                    Map<String, Set<String>> crossSchemaFields,
                                    Map<String, Map<String, String>> crossSchemaFieldTypes,
                                    List<Subgraph> allSubgraphs,
                                    ValidationResult.Builder builder) {
        for (GraphQLFieldDefinition field : type.getFieldDefinitions()) {
            // @require is on field arguments, and the declaring type is the parent type
            validateFieldArguments(type.getName(), field, currentSubgraph, schemaName,
                crossSchemaFields, crossSchemaFieldTypes, allSubgraphs, builder);
        }
    }

    private void validateInterfaceType(GraphQLInterfaceType type, Subgraph currentSubgraph,
                                       String schemaName,
                                       Map<String, Set<String>> crossSchemaFields,
                                       Map<String, Map<String, String>> crossSchemaFieldTypes,
                                       List<Subgraph> allSubgraphs,
                                       ValidationResult.Builder builder) {
        for (GraphQLFieldDefinition field : type.getFieldDefinitions()) {
            validateFieldArguments(type.getName(), field, currentSubgraph, schemaName,
                crossSchemaFields, crossSchemaFieldTypes, allSubgraphs, builder);
        }
    }

    private void validateFieldArguments(String typeName, GraphQLFieldDefinition field,
                                        Subgraph currentSubgraph, String schemaName,
                                        Map<String, Set<String>> crossSchemaFields,
                                        Map<String, Map<String, String>> crossSchemaFieldTypes,
                                        List<Subgraph> allSubgraphs,
                                        ValidationResult.Builder builder) {
        // For @require, the declaring type is the parent type (where the field is defined)
        String declaringTypeName = typeName;

        for (GraphQLArgument arg : field.getArguments()) {
            if (!arg.hasAppliedDirective(REQUIRE)) {
                continue;
            }

            GraphQLAppliedDirective requireDirective = arg.getAppliedDirective(REQUIRE);
            GraphQLAppliedDirectiveArgument fieldArg = requireDirective.getArgument("field");
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
                validateSelectedValue(selectedValue, declaringTypeName, coordinate,
                    currentSubgraph, schemaName, crossSchemaFields, crossSchemaFieldTypes,
                    allSubgraphs, builder);

                // Validate type condition coverage: if type conditions are used,
                // all concrete types must be covered when the argument is non-null
                validateTypeConditionCoverage(selectedValue, declaringTypeName, arg, coordinate,
                    schemaName, currentSubgraph, allSubgraphs, builder);
            } catch (Exception e) {
                // Syntax errors are handled by FieldSelectionMapSyntaxRule
            }
        }
    }

    private void validateSelectedValue(SelectedValue selectedValue, String currentTypeName,
                                       String coordinate, Subgraph currentSubgraph,
                                       String schemaName,
                                       Map<String, Set<String>> crossSchemaFields,
                                       Map<String, Map<String, String>> crossSchemaFieldTypes,
                                       List<Subgraph> allSubgraphs,
                                       ValidationResult.Builder builder) {
        for (Alternative alt : selectedValue.alternatives()) {
            validateAlternative(alt, currentTypeName, coordinate, currentSubgraph, schemaName,
                crossSchemaFields, crossSchemaFieldTypes, allSubgraphs, builder);
        }
    }

    private void validateAlternative(Alternative alt, String currentTypeName,
                                     String coordinate, Subgraph currentSubgraph,
                                     String schemaName,
                                     Map<String, Set<String>> crossSchemaFields,
                                     Map<String, Map<String, String>> crossSchemaFieldTypes,
                                     List<Subgraph> allSubgraphs,
                                     ValidationResult.Builder builder) {
        if (alt instanceof Path path) {
            validatePath(path, currentTypeName, coordinate, currentSubgraph, schemaName,
                crossSchemaFields, crossSchemaFieldTypes, allSubgraphs, builder);
        } else if (alt instanceof ObjectSelection objectSelection) {
            validateObjectSelection(objectSelection, currentTypeName, coordinate, currentSubgraph,
                schemaName, crossSchemaFields, crossSchemaFieldTypes, allSubgraphs, builder);
        } else if (alt instanceof ListSelection listSelection) {
            validateListSelection(listSelection, currentTypeName, coordinate, currentSubgraph,
                schemaName, crossSchemaFields, crossSchemaFieldTypes, allSubgraphs, builder);
        }
    }

    private void validatePath(Path path, String currentTypeName,
                              String coordinate, Subgraph currentSubgraph,
                              String schemaName,
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
                    "@require directive at '%s' in schema '%s' references non-existent type '%s'.",
                    coordinate, schemaName, typeName
                );
                builder.addError(CODE, message, coordinate, schemaName);
                return;
            }
        }

        for (PathSegment segment : path.segments()) {
            String fieldName = segment.fieldName();

            // Check if field exists in other schemas (including on subtypes/implementing types)
            // For @require, fields must come from OTHER schemas
            boolean existsInOtherSchemas = CrossSchemaFieldResolver.isFieldInOtherSchemasWithSubtypes(
                typeName, fieldName, allSubgraphs, schemaName);

            if (!existsInOtherSchemas) {
                // Check if field exists in same schema (for better error message)
                boolean existsInSameSchema = CrossSchemaFieldResolver.isFieldInAnySchema(
                    List.of(currentSubgraph), typeName, fieldName);

                if (existsInSameSchema) {
                    // Field exists only in the same schema - this is the specific @require error
                    String message = String.format(
                        "@require directive at '%s' in schema '%s' references field '%s' on type '%s' " +
                        "which only exists in the same schema. Required fields must come from other schemas.",
                        coordinate, schemaName, fieldName, typeName
                    );
                    builder.addError(CODE, message, coordinate, schemaName);
                } else {
                    // Field doesn't exist anywhere
                    String message = String.format(
                        "@require directive at '%s' in schema '%s' references field '%s' on type '%s' " +
                        "which does not exist in any schema.",
                        coordinate, schemaName, fieldName, typeName
                    );
                    builder.addError(CODE, message, coordinate, schemaName);
                }
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
                        "@require directive at '%s' in schema '%s' references non-existent type '%s'.",
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
                                         String coordinate, Subgraph currentSubgraph,
                                         String schemaName,
                                         Map<String, Set<String>> crossSchemaFields,
                                         Map<String, Map<String, String>> crossSchemaFieldTypes,
                                         List<Subgraph> allSubgraphs,
                                         ValidationResult.Builder builder) {
        String typeName = currentTypeName;

        // First validate path prefix if present
        if (objectSelection.pathPrefix() != null) {
            validatePath(objectSelection.pathPrefix(), currentTypeName, coordinate, currentSubgraph,
                schemaName, crossSchemaFields, crossSchemaFieldTypes, allSubgraphs, builder);

            // Update type context based on path
            typeName = getTypeAfterPath(objectSelection.pathPrefix(), currentTypeName, crossSchemaFieldTypes);
        }

        // Validate object fields - the field.name() is an alias (mapping key for input),
        // the field.value() contains the actual path being required from the schema
        for (ObjectField field : objectSelection.fields()) {
            // Only validate the value (path) - the name is just an alias for mapping
            if (field.value() != null) {
                validateSelectedValue(field.value(), typeName, coordinate, currentSubgraph,
                    schemaName, crossSchemaFields, crossSchemaFieldTypes, allSubgraphs, builder);
            }
        }
    }

    private void validateListSelection(ListSelection listSelection, String currentTypeName,
                                       String coordinate, Subgraph currentSubgraph,
                                       String schemaName,
                                       Map<String, Set<String>> crossSchemaFields,
                                       Map<String, Map<String, String>> crossSchemaFieldTypes,
                                       List<Subgraph> allSubgraphs,
                                       ValidationResult.Builder builder) {
        String typeName = currentTypeName;

        // First validate path prefix if present
        if (listSelection.pathPrefix() != null) {
            validatePath(listSelection.pathPrefix(), currentTypeName, coordinate, currentSubgraph,
                schemaName, crossSchemaFields, crossSchemaFieldTypes, allSubgraphs, builder);

            // Update type context based on path
            typeName = getTypeAfterPath(listSelection.pathPrefix(), currentTypeName, crossSchemaFieldTypes);
        }

        // Validate element value
        if (listSelection.elementValue() != null) {
            validateSelectedValue(listSelection.elementValue(), typeName, coordinate, currentSubgraph,
                schemaName, crossSchemaFields, crossSchemaFieldTypes, allSubgraphs, builder);
        }
    }

    /**
     * Extracts a string value from a directive argument.
     * Handles both raw String and StringValue (GraphQL AST) types.
     */
    /**
     * Computes the type context after traversing a path.
     * Handles both initial type conditions and infix type conditions.
     */
    private String getTypeAfterPath(Path path, String startingType,
                                    Map<String, Map<String, String>> crossSchemaFieldTypes) {
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
                String nextType = CrossSchemaFieldResolver.getFieldReturnType(
                    typeName, segment.fieldName(), crossSchemaFieldTypes);
                if (nextType != null) {
                    typeName = nextType;
                }
            }
        }

        return typeName;
    }

    /**
     * Validates that when type conditions are used, all concrete types of the abstract type
     * are covered by some alternative. This is required when the argument is non-null.
     *
     * For example, if a field returns Media (interface with Book, Movie, TVShow), and
     * @require uses "media<Movie>.imdbCode", this is only valid if the argument is nullable
     * (since Book and TVShow would have no value).
     */
    private void validateTypeConditionCoverage(SelectedValue selectedValue, String declaringTypeName,
                                               GraphQLArgument arg, String coordinate,
                                               String schemaName, Subgraph currentSubgraph,
                                               List<Subgraph> allSubgraphs,
                                               ValidationResult.Builder builder) {
        // Check if argument type is non-null
        boolean argIsNonNull = GraphQLTypeUtil.isNonNull(arg.getType());

        // For input object types, we need to check each field's nullability separately
        GraphQLType unwrappedArgType = GraphQLTypeUtil.unwrapAll(arg.getType());

        // Collect type condition coverage from all alternatives
        // Map: narrowing context (parent type name + field path) -> covered types
        Map<NarrowingContext, Set<String>> coverageMap = new HashMap<>();
        collectTypeConditionCoverage(selectedValue, declaringTypeName, "", allSubgraphs, coverageMap);

        // For each narrowing context, check if all concrete types are covered
        for (Map.Entry<NarrowingContext, Set<String>> entry : coverageMap.entrySet()) {
            NarrowingContext context = entry.getKey();
            Set<String> coveredTypes = entry.getValue();

            // Get all concrete types that could exist at this narrowing point
            Set<String> allConcreteTypes = CrossSchemaFieldResolver.getConcreteTypes(allSubgraphs, context.parentTypeName);

            if (allConcreteTypes.isEmpty()) {
                // Parent type doesn't exist or has no concrete types - other validation will catch this
                continue;
            }

            // Check if all concrete types are covered
            Set<String> uncoveredTypes = new HashSet<>(allConcreteTypes);
            uncoveredTypes.removeAll(coveredTypes);

            if (!uncoveredTypes.isEmpty()) {
                // Some types are not covered - check nullability
                boolean requiresFullCoverage = isNonNullAtPath(unwrappedArgType, context.inputPath,
                    argIsNonNull, currentSubgraph);

                if (requiresFullCoverage) {
                    String message = String.format(
                        "@require directive at '%s' in schema '%s' uses type conditions that do not cover " +
                        "all concrete types of '%s'. Uncovered types: %s. Either cover all types with " +
                        "alternatives or make the argument/field nullable.",
                        coordinate, schemaName, context.parentTypeName, uncoveredTypes
                    );
                    builder.addError(CODE, message, coordinate, schemaName);
                }
            }
        }
    }

    /**
     * Represents a type narrowing context - where a type condition narrows an abstract type.
     */
    private record NarrowingContext(String parentTypeName, String inputPath) {}

    /**
     * Collects type condition coverage from a SelectedValue and all its alternatives.
     * Populates coverageMap with which concrete types are covered at each narrowing point.
     */
    private void collectTypeConditionCoverage(SelectedValue selectedValue, String currentTypeName,
                                              String inputPath, List<Subgraph> allSubgraphs,
                                              Map<NarrowingContext, Set<String>> coverageMap) {
        for (Alternative alt : selectedValue.alternatives()) {
            collectTypeConditionCoverageFromAlternative(alt, currentTypeName, inputPath, allSubgraphs, coverageMap);
        }
    }

    private void collectTypeConditionCoverageFromAlternative(Alternative alt, String currentTypeName,
                                                             String inputPath, List<Subgraph> allSubgraphs,
                                                             Map<NarrowingContext, Set<String>> coverageMap) {
        if (alt instanceof Path path) {
            collectTypeConditionCoverageFromPath(path, currentTypeName, inputPath, allSubgraphs, coverageMap);
        } else if (alt instanceof ObjectSelection objectSelection) {
            collectTypeConditionCoverageFromObjectSelection(objectSelection, currentTypeName, inputPath,
                allSubgraphs, coverageMap);
        } else if (alt instanceof ListSelection listSelection) {
            collectTypeConditionCoverageFromListSelection(listSelection, currentTypeName, inputPath,
                allSubgraphs, coverageMap);
        }
    }

    private void collectTypeConditionCoverageFromPath(Path path, String currentTypeName, String inputPath,
                                                      List<Subgraph> allSubgraphs,
                                                      Map<NarrowingContext, Set<String>> coverageMap) {
        String typeName = currentTypeName;

        // Handle initial type condition (e.g., <Movie> in "<Movie>.imdbCode")
        if (path.hasInitialTypeCondition()) {
            String narrowedType = path.initialTypeCondition();
            // The parent type being narrowed is currentTypeName (the declaring type)
            if (CrossSchemaFieldResolver.isAbstractType(allSubgraphs, typeName)) {
                NarrowingContext context = new NarrowingContext(typeName, inputPath);
                coverageMap.computeIfAbsent(context, k -> new HashSet<>()).add(narrowedType);
            }
            typeName = narrowedType;
        }

        // Process path segments
        for (PathSegment segment : path.segments()) {
            String fieldName = segment.fieldName();

            // Handle infix type condition (e.g., <Book> in "media<Book>.isbn")
            if (segment.hasTypeCondition()) {
                String narrowedType = segment.typeCondition();
                // Get the return type of the field before narrowing
                String fieldReturnType = CrossSchemaFieldResolver.getFieldReturnTypeFromAnySchema(
                    allSubgraphs, typeName, fieldName);

                if (fieldReturnType != null && CrossSchemaFieldResolver.isAbstractType(allSubgraphs, fieldReturnType)) {
                    // Record that this concrete type is covered for this narrowing point
                    String narrowingPath = inputPath.isEmpty() ? fieldName : inputPath + "." + fieldName;
                    NarrowingContext context = new NarrowingContext(fieldReturnType, narrowingPath);
                    coverageMap.computeIfAbsent(context, k -> new HashSet<>()).add(narrowedType);
                }
                typeName = narrowedType;
            } else {
                // No type condition - get return type for next segment
                String nextType = CrossSchemaFieldResolver.getFieldReturnTypeFromAnySchema(
                    allSubgraphs, typeName, fieldName);
                if (nextType != null) {
                    typeName = nextType;
                }
            }
        }
    }

    private void collectTypeConditionCoverageFromObjectSelection(ObjectSelection objectSelection,
                                                                 String currentTypeName, String inputPath,
                                                                 List<Subgraph> allSubgraphs,
                                                                 Map<NarrowingContext, Set<String>> coverageMap) {
        String typeName = currentTypeName;

        // Handle path prefix if present
        if (objectSelection.pathPrefix() != null) {
            collectTypeConditionCoverageFromPath(objectSelection.pathPrefix(), currentTypeName, inputPath,
                allSubgraphs, coverageMap);
            typeName = getTypeAfterPathForCoverage(objectSelection.pathPrefix(), currentTypeName, allSubgraphs);
        }

        // Process object fields
        for (ObjectField field : objectSelection.fields()) {
            if (field.value() != null) {
                // The input path includes this field name for nullability checking
                String fieldInputPath = inputPath.isEmpty() ? field.name() : inputPath + "." + field.name();
                collectTypeConditionCoverage(field.value(), typeName, fieldInputPath, allSubgraphs, coverageMap);
            }
        }
    }

    private void collectTypeConditionCoverageFromListSelection(ListSelection listSelection,
                                                               String currentTypeName, String inputPath,
                                                               List<Subgraph> allSubgraphs,
                                                               Map<NarrowingContext, Set<String>> coverageMap) {
        String typeName = currentTypeName;

        // Handle path prefix if present
        if (listSelection.pathPrefix() != null) {
            collectTypeConditionCoverageFromPath(listSelection.pathPrefix(), currentTypeName, inputPath,
                allSubgraphs, coverageMap);
            typeName = getTypeAfterPathForCoverage(listSelection.pathPrefix(), currentTypeName, allSubgraphs);
        }

        // Process element value
        if (listSelection.elementValue() != null) {
            collectTypeConditionCoverage(listSelection.elementValue(), typeName, inputPath, allSubgraphs, coverageMap);
        }
    }

    /**
     * Gets the type after traversing a path (for coverage collection).
     */
    private String getTypeAfterPathForCoverage(Path path, String startingType, List<Subgraph> allSubgraphs) {
        String typeName = startingType;

        if (path.hasInitialTypeCondition()) {
            typeName = path.initialTypeCondition();
        }

        for (PathSegment segment : path.segments()) {
            if (segment.hasTypeCondition()) {
                typeName = segment.typeCondition();
            } else {
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
     * Checks if the value at a given input path is non-null.
     * For simple arguments, checks if the argument type is non-null.
     * For input objects, navigates to the field and checks its nullability.
     */
    private boolean isNonNullAtPath(GraphQLType argType, String inputPath, boolean argIsNonNull,
                                    Subgraph currentSubgraph) {
        if (inputPath.isEmpty()) {
            // Root level - use argument nullability
            return argIsNonNull;
        }

        // For input objects, navigate to the field
        if (argType instanceof GraphQLInputObjectType inputObjectType) {
            String[] pathParts = inputPath.split("\\.");
            GraphQLType currentType = inputObjectType;

            for (String part : pathParts) {
                if (currentType instanceof GraphQLInputObjectType currentInputType) {
                    GraphQLInputObjectField field = currentInputType.getField(part);
                    if (field == null) {
                        // Field not found - can't determine, assume non-null to be safe
                        return true;
                    }
                    if (!GraphQLTypeUtil.isNonNull(field.getType())) {
                        // This field is nullable, so incomplete coverage is OK
                        return false;
                    }
                    currentType = GraphQLTypeUtil.unwrapAll(field.getType());
                } else {
                    // Not an input object, can't navigate further
                    break;
                }
            }
            // Reached the end and all fields were non-null
            return true;
        }

        // For non-object types, use argument nullability
        return argIsNonNull;
    }

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
