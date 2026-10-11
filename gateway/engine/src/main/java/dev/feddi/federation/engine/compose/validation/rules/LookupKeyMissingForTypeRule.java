package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.CrossSchemaFieldResolver;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import dev.feddi.federation.engine.parser.FieldSelectionMap.Alternative;
import dev.feddi.federation.engine.parser.FieldSelectionMap.ListSelection;
import dev.feddi.federation.engine.parser.FieldSelectionMap.ObjectField;
import dev.feddi.federation.engine.parser.FieldSelectionMap.ObjectSelection;
import dev.feddi.federation.engine.parser.FieldSelectionMap.Path;
import dev.feddi.federation.engine.parser.FieldSelectionMap.PathSegment;
import dev.feddi.federation.engine.parser.FieldSelectionMap.SelectedValue;
import dev.feddi.federation.engine.parser.FieldSelectionMapParser;
import dev.feddi.federation.engine.parser.InvalidSyntaxException;
import graphql.language.StringValue;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnionType;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import static dev.feddi.federation.engine.compose.FederationDirectives.IS;
import static dev.feddi.federation.engine.compose.FederationDirectives.LOOKUP;
import static dev.feddi.federation.engine.compose.FederationDirectives.REQUIRE;

/**
 * Validates that every argument of a lookup field maps to a field of every possible type of its return type:
 * by its name, or through an alternative of its {@code @is} selection map whose type conditions admit the type.
 *
 * <p>The possible types are those of the lookup's source schema. The fields may be defined in any schema,
 * as for {@code IS_INVALID_FIELDS}, whose selection maps resolve across schemas.
 *
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Lookup-Key-Missing-For-Type
 */
public final class LookupKeyMissingForTypeRule implements ValidationRule {

    private static final String CODE = "LOOKUP_KEY_MISSING_FOR_TYPE";

    @Override
    public ValidationPhase phase() {
        return ValidationPhase.PRE_MERGE;
    }

    @Override
    public String name() {
        return "LookupKeyMissingForTypeRule";
    }

    @Override
    public ValidationResult validate(List<Subgraph> subgraphs) {
        ValidationResult.Builder builder = ValidationResult.builder();
        for (Subgraph subgraph : subgraphs) {
            GraphQLSchema schema = subgraph.schema();
            for (GraphQLNamedType type : schema.getAllTypesAsList()) {
                if (type instanceof GraphQLFieldsContainer container) {
                    for (GraphQLFieldDefinition field : container.getFieldDefinitions()) {
                        if (field.hasAppliedDirective(LOOKUP)) {
                            validateLookup(container.getName() + "." + field.getName(), field, subgraph,
                                subgraphs, builder);
                        }
                    }
                }
            }
        }
        return builder.build();
    }

    private void validateLookup(String coordinate, GraphQLFieldDefinition lookup, Subgraph subgraph,
                                List<Subgraph> subgraphs, ValidationResult.Builder builder) {
        List<GraphQLObjectType> possibleTypes = possibleTypes(subgraph.schema(),
            GraphQLTypeUtil.unwrapAll(lookup.getType()));
        for (GraphQLArgument argument : lookup.getArguments()) {
            if (argument.hasAppliedDirective(REQUIRE)) {
                continue;
            }
            SelectedValue isMap = isSelectionMap(argument);
            if (argument.hasAppliedDirective(IS) && isMap == null) {
                continue; // FieldSelectionMapSyntaxRule reports it
            }
            List<String> unmapped = new ArrayList<>();
            for (GraphQLObjectType possibleType : possibleTypes) {
                boolean mappable = isMap == null
                    ? CrossSchemaFieldResolver.isFieldDefinedOn(subgraphs, possibleType.getName(), argument.getName())
                    : mappable(isMap, possibleType.getName(), true, subgraph.schema(), subgraphs);
                if (!mappable) {
                    unmapped.add(possibleType.getName());
                }
            }
            if (!unmapped.isEmpty()) {
                String argumentCoordinate = coordinate + "(" + argument.getName() + ":)";
                builder.addError(CODE, String.format(
                    "Lookup argument '%s' in schema '%s' cannot be mapped to a field of the possible type(s) %s; "
                        + "every possible type of a lookup's return type must be resolvable by its arguments.",
                    argumentCoordinate, subgraph.name(), unmapped), argumentCoordinate, subgraph.name());
            }
        }
    }

    private static List<GraphQLObjectType> possibleTypes(GraphQLSchema schema, GraphQLType type) {
        return switch (type) {
            case GraphQLObjectType objectType -> List.of(objectType);
            case GraphQLInterfaceType interfaceType -> schema.getImplementations(interfaceType);
            case GraphQLUnionType unionType -> unionType.getTypes().stream()
                .filter(GraphQLObjectType.class::isInstance).map(GraphQLObjectType.class::cast).toList();
            default -> List.of();
        };
    }

    private static @Nullable SelectedValue isSelectionMap(GraphQLArgument argument) {
        if (!argument.hasAppliedDirective(IS)) {
            return null;
        }
        var field = argument.getAppliedDirective(IS).getArgument("field");
        Object value = field == null ? null : field.getValue();
        String text = value instanceof StringValue stringValue ? stringValue.getValue()
            : value == null ? null : value.toString();
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return FieldSelectionMapParser.parseFieldSelectionMap(text);
        } catch (InvalidSyntaxException e) {
            return null;
        }
    }

    /**
     * Whether some alternative of {@code value} selects only fields defined for {@code typeName}. At the root,
     * where {@code typeName} is the possible type, a type condition must also admit it; below a field, type
     * conditions only narrow.
     */
    private static boolean mappable(SelectedValue value, String typeName, boolean root, GraphQLSchema schema,
                                    List<Subgraph> subgraphs) {
        for (Alternative alternative : value.alternatives()) {
            if (mappable(alternative, typeName, root, schema, subgraphs)) {
                return true;
            }
        }
        return false;
    }

    private static boolean mappable(Alternative alternative, String typeName, boolean root, GraphQLSchema schema,
                                    List<Subgraph> subgraphs) {
        return switch (alternative) {
            case Path path -> walk(path, typeName, root, schema, subgraphs) != null;
            case ObjectSelection object -> {
                String objectType = object.pathPrefix() == null ? typeName
                    : walk(object.pathPrefix(), typeName, root, schema, subgraphs);
                boolean fieldsRoot = root && object.pathPrefix() == null;
                yield objectType != null && object.fields().stream()
                    .map(ObjectField::value)
                    .allMatch(fieldValue -> mappable(fieldValue, objectType, fieldsRoot, schema, subgraphs));
            }
            case ListSelection list -> {
                String elementType = list.pathPrefix() == null ? typeName
                    : walk(list.pathPrefix(), typeName, root, schema, subgraphs);
                yield elementType != null && mappable(list.elementValue(), elementType,
                    root && list.pathPrefix() == null, schema, subgraphs);
            }
        };
    }

    /** The type a path leads to from {@code typeName}, or null if it does not apply to or select fields of it. */
    private static @Nullable String walk(Path path, String typeName, boolean root, GraphQLSchema schema,
                                         List<Subgraph> subgraphs) {
        String current = typeName;
        if (path.hasInitialTypeCondition()) {
            if (root && !admits(path.initialTypeCondition(), typeName, schema)) {
                return null;
            }
            if (!root) {
                current = path.initialTypeCondition();
            }
        }
        for (PathSegment segment : path.segments()) {
            if (!CrossSchemaFieldResolver.isFieldDefinedOn(subgraphs, current, segment.fieldName())) {
                return null;
            }
            current = segment.hasTypeCondition() ? segment.typeCondition()
                : CrossSchemaFieldResolver.getFieldReturnTypeFromAnySchema(subgraphs, current, segment.fieldName());
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private static boolean admits(String typeCondition, String typeName, GraphQLSchema schema) {
        if (typeCondition.equals(typeName)) {
            return true;
        }
        return schema.getType(typeCondition) instanceof GraphQLNamedType condition
            && (condition instanceof GraphQLInterfaceType || condition instanceof GraphQLUnionType)
            && schema.getType(typeName) instanceof GraphQLObjectType objectType
            && schema.isPossibleType(condition, objectType);
    }
}
