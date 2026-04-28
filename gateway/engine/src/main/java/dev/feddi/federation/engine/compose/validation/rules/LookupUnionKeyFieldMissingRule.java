package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import graphql.language.StringValue;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLAppliedDirectiveArgument;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLNamedOutputType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnionType;

import java.util.ArrayList;
import java.util.List;

import static dev.feddi.federation.engine.compose.FederationDirectives.IS;
import static dev.feddi.federation.engine.compose.FederationDirectives.LOOKUP;
import static dev.feddi.federation.engine.compose.FederationDirectives.REQUIRE;

/**
 * Validates that @is referenced fields exist on ALL union member types when a @lookup returns a union.
 *
 * When a @lookup field returns a union type, the key fields (from @is directives) must exist
 * on every member type of the union. This is because:
 * 1. The lookup can return any member type
 * 2. The planner needs to extract key values for subsequent lookups
 * 3. If a member type is returned that lacks the key field, extraction fails
 *
 * Example that should fail:
 *   product(id: ID! @is(field: "id"), categoryId: Int @is(field: "categoryId")): Product @lookup
 *   union Product = Electronics | Clothing
 *   type Electronics { id: ID!, categoryId: Int, ... }
 *   type Clothing { id: ID!, ... }  # Missing categoryId!
 */
public final class LookupUnionKeyFieldMissingRule implements ValidationRule {

    private static final String CODE = "LOOKUP_UNION_KEY_FIELD_MISSING";

    @Override
    public ValidationPhase phase() {
        return ValidationPhase.PRE_MERGE;
    }

    @Override
    public String name() {
        return "LookupUnionKeyFieldMissingRule";
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

        for (GraphQLType type : subgraph.schema().getAllTypesAsList()) {
            if (type instanceof GraphQLObjectType objectType) {
                validateObjectType(objectType, schemaName, allSubgraphs, builder);
            }
        }
    }

    private void validateObjectType(GraphQLObjectType type, String schemaName,
                                    List<Subgraph> allSubgraphs,
                                    ValidationResult.Builder builder) {
        for (GraphQLFieldDefinition field : type.getFieldDefinitions()) {
            if (!field.hasAppliedDirective(LOOKUP)) {
                continue;
            }

            // Check if the return type is a union
            GraphQLType returnType = GraphQLTypeUtil.unwrapAll(field.getType());
            if (!(returnType instanceof GraphQLUnionType unionType)) {
                continue;
            }

            // Collect all @is field references
            List<IsFieldReference> isFields = extractIsFields(field);
            if (isFields.isEmpty()) {
                continue;
            }

            // Validate each @is field exists on ALL union member types
            for (IsFieldReference isField : isFields) {
                validateFieldOnAllMembers(unionType, isField, type.getName(), field.getName(),
                    schemaName, allSubgraphs, builder);
            }
        }
    }

    private List<IsFieldReference> extractIsFields(GraphQLFieldDefinition field) {
        List<IsFieldReference> result = new ArrayList<>();

        for (GraphQLArgument arg : field.getArguments()) {
            String fieldPath;

            if (arg.hasAppliedDirective(IS)) {
                GraphQLAppliedDirective isDirective = arg.getAppliedDirective(IS);
                GraphQLAppliedDirectiveArgument fieldArg = isDirective.getArgument("field");
                fieldPath = extractStringValue(fieldArg);
            } else if (!arg.hasAppliedDirective(REQUIRE)) {
                // No @is directive and not @require - use argument name as implicit field path
                fieldPath = arg.getName();
            } else {
                continue;
            }

            if (fieldPath != null && !fieldPath.isBlank()) {
                result.add(new IsFieldReference(arg.getName(), fieldPath));
            }
        }

        return result;
    }

    private void validateFieldOnAllMembers(GraphQLUnionType unionType, IsFieldReference isField,
                                           String parentTypeName, String fieldName,
                                           String schemaName, List<Subgraph> allSubgraphs,
                                           ValidationResult.Builder builder) {
        String fieldPath = isField.fieldPath();
        // For simplicity, handle single-segment paths (most common case)
        // Nested paths like "foo.bar" would need recursive resolution
        String rootFieldName = fieldPath.contains(".") ? fieldPath.split("\\.")[0] : fieldPath;

        List<String> missingOnMembers = new ArrayList<>();

        for (GraphQLNamedOutputType memberType : unionType.getTypes()) {
            String memberTypeName = memberType.getName();

            // Check if the field exists on this member type in any schema
            boolean fieldExists = false;
            for (Subgraph subgraph : allSubgraphs) {
                GraphQLType type = subgraph.schema().getType(memberTypeName);
                if (type instanceof GraphQLFieldsContainer fieldsContainer) {
                    if (fieldsContainer.getFieldDefinition(rootFieldName) != null) {
                        fieldExists = true;
                        break;
                    }
                }
            }

            if (!fieldExists) {
                missingOnMembers.add(memberTypeName);
            }
        }

        if (!missingOnMembers.isEmpty()) {
            String coordinate = parentTypeName + "." + fieldName + "(" + isField.argumentName() + ")";
            String message = String.format(
                "@is directive at '%s' in schema '%s' references field '%s' which is missing on union member type(s): %s. " +
                "When a @lookup returns a union, all @is fields must exist on every member type.",
                coordinate, schemaName, fieldPath, String.join(", ", missingOnMembers)
            );
            builder.addError(CODE, message, coordinate, schemaName);
        }
    }

    private String extractStringValue(GraphQLAppliedDirectiveArgument arg) {
        if (arg == null) return null;
        Object value = arg.getValue();
        if (value instanceof StringValue stringValue) {
            return stringValue.getValue();
        }
        return value != null ? value.toString() : null;
    }

    private record IsFieldReference(String argumentName, String fieldPath) {}
}
