package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.FieldSelectionArguments;
import dev.feddi.federation.engine.compose.validation.SelectionMapWalker;
import dev.feddi.federation.engine.compose.validation.SourceSchemas;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import dev.feddi.federation.engine.parser.FieldSelectionMap.PathSegment;
import dev.feddi.federation.engine.parser.FieldSelectionMap.SelectedValue;
import dev.feddi.federation.engine.parser.FieldSelectionMapParser;
import dev.feddi.federation.engine.parser.InvalidSyntaxException;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.feddi.federation.engine.compose.FederationDirectives.REQUIRE;
import static dev.feddi.federation.engine.compose.validation.SchemaElements.stringValue;

/**
 * Validates that @require directive field references exist in OTHER schemas (not the defining schema).
 *
 * The @require directive declares that a field argument depends on other fields
 * that must be fetched from OTHER schemas. If the required field exists only in
 * the same schema, or doesn't exist at all, this is an error. Type conditions must name existing types and,
 * where the value they select is non-null, cover all possible types of the type they narrow.
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
        SourceSchemas all = SourceSchemas.of(subgraphs);
        SelectionMapWalker walker = new SelectionMapWalker(all);
        for (Subgraph subgraph : subgraphs) {
            SourceSchemas others = all.excluding(subgraph.name());
            SourceSchemas own = SourceSchemas.of(List.of(subgraph));
            for (GraphQLType type : subgraph.schema().getAllTypesAsList()) {
                if (!(type instanceof GraphQLFieldsContainer container)) {
                    continue;
                }
                for (GraphQLFieldDefinition field : container.getFieldDefinitions()) {
                    for (GraphQLArgument argument : field.getArguments()) {
                        SelectedValue map = requireSelectionMap(argument);
                        if (map == null) {
                            continue;
                        }
                        // The map is rooted at the type that declares the field
                        String coordinate = container.getName() + "." + field.getName() + "(" + argument.getName() + ")";
                        walker.walk(map, container.getName(),
                            new FieldChecks(all, others, own, coordinate, subgraph.name(), builder));
                        Map<NarrowingContext, Set<String>> coverage = new HashMap<>();
                        walker.walk(map, container.getName(), new CoverageCollector(all, coverage));
                        validateTypeConditionCoverage(coverage, argument, coordinate, subgraph.name(), all, builder);
                    }
                }
            }
        }
        return builder.build();
    }

    private static SelectedValue requireSelectionMap(GraphQLArgument argument) {
        if (!argument.hasAppliedDirective(REQUIRE)) {
            return null;
        }
        String text = stringValue(argument.getAppliedDirective(REQUIRE).getArgument("field"));
        if (text == null || text.isBlank()) {
            return null; // Type validation is done by another rule
        }
        try {
            return FieldSelectionMapParser.parseFieldSelectionMap(text);
        } catch (InvalidSyntaxException e) {
            return null; // FieldSelectionMapSyntaxRule reports it
        }
    }

    /** Reports required fields that no other schema defines and type conditions that name no type. */
    private record FieldChecks(SourceSchemas all, SourceSchemas others, SourceSchemas own, String coordinate,
                               String schemaName, ValidationResult.Builder builder)
            implements SelectionMapWalker.Visitor {

        @Override
        public boolean field(String parentType, PathSegment segment) {
            String fieldName = segment.fieldName();
            SourceSchemas.FieldIn field = others.field(parentType, fieldName);
            if (field == null) {
                if (own.hasFieldOnTypeOrSubtype(parentType, fieldName)) {
                    // The specific @require error: required fields must come from other schemas
                    error(String.format("@require directive at '%s' in schema '%s' references field '%s' on type '%s' "
                        + "which only exists in the same schema. Required fields must come from other schemas.",
                        coordinate, schemaName, fieldName, parentType));
                } else {
                    error(String.format("@require directive at '%s' in schema '%s' references field '%s' on type '%s' "
                        + "which does not exist in any schema.", coordinate, schemaName, fieldName, parentType));
                }
                return false;
            }
            // The arguments the field is selected with (Appendix A, Path Field Argument Validity)
            for (String problem : FieldSelectionArguments.problems(parentType + "." + fieldName, field.definition(),
                    segment.arguments(), field.schema())) {
                error(String.format("@require directive at '%s' in schema '%s': %s.", coordinate, schemaName, problem));
            }
            return true;
        }

        @Override
        public boolean typeCondition(String scopeType, String condition, String inputPath) {
            if (!all.hasType(condition)) {
                error(String.format("@require directive at '%s' in schema '%s' references non-existent type '%s'.",
                    coordinate, schemaName, condition));
                return false;
            }
            return true;
        }

        private void error(String message) {
            builder.addError(CODE, message, coordinate, schemaName);
        }
    }

    /**
     * A point where a type condition narrows an abstract type: the type, and the path of input object fields to
     * the value (plus the field the condition follows).
     */
    private record NarrowingContext(String parentTypeName, String inputPath) {
    }

    /** Collects which types the type conditions cover at each point where they narrow an abstract type. */
    private record CoverageCollector(SourceSchemas all, Map<NarrowingContext, Set<String>> coverage)
            implements SelectionMapWalker.Visitor {

        @Override
        public boolean field(String parentType, PathSegment segment) {
            return true;
        }

        @Override
        public boolean typeCondition(String scopeType, String condition, String inputPath) {
            if (scopeType != null && all.isAbstract(scopeType)) {
                coverage.computeIfAbsent(new NarrowingContext(scopeType, inputPath), k -> new HashSet<>())
                    .add(condition);
            }
            return true;
        }
    }

    /**
     * Validates that when type conditions are used, all concrete types of the abstract type
     * are covered by some alternative. This is required when the argument is non-null.
     *
     * For example, if a field returns Media (interface with Book, Movie, TVShow), and
     * @require uses "media<Movie>.imdbCode", this is only valid if the argument is nullable
     * (since Book and TVShow would have no value).
     */
    private void validateTypeConditionCoverage(Map<NarrowingContext, Set<String>> coverage, GraphQLArgument arg,
                                               String coordinate, String schemaName, SourceSchemas all,
                                               ValidationResult.Builder builder) {
        boolean argIsNonNull = GraphQLTypeUtil.isNonNull(arg.getType());
        GraphQLType unwrappedArgType = GraphQLTypeUtil.unwrapAll(arg.getType());

        for (Map.Entry<NarrowingContext, Set<String>> entry : coverage.entrySet()) {
            NarrowingContext context = entry.getKey();
            Set<String> uncoveredTypes = new HashSet<>(all.concreteTypes(context.parentTypeName()));
            if (uncoveredTypes.isEmpty()) {
                // Parent type doesn't exist or has no concrete types - other validation will catch this
                continue;
            }
            uncoveredTypes.removeAll(entry.getValue());
            if (!uncoveredTypes.isEmpty() && isNonNullAtPath(unwrappedArgType, context.inputPath(), argIsNonNull)) {
                builder.addError(CODE, String.format(
                    "@require directive at '%s' in schema '%s' uses type conditions that do not cover " +
                    "all concrete types of '%s'. Uncovered types: %s. Either cover all types with " +
                    "alternatives or make the argument/field nullable.",
                    coordinate, schemaName, context.parentTypeName(), uncoveredTypes), coordinate, schemaName);
            }
        }
    }

    /**
     * Checks if the value at a given input path is non-null.
     * For simple arguments, checks if the argument type is non-null.
     * For input objects, navigates to the field and checks its nullability.
     */
    private boolean isNonNullAtPath(GraphQLType argType, String inputPath, boolean argIsNonNull) {
        if (inputPath.isEmpty() || !(argType instanceof GraphQLInputObjectType)) {
            return argIsNonNull;
        }
        GraphQLType currentType = argType;
        for (String part : inputPath.split("\\.")) {
            if (!(currentType instanceof GraphQLInputObjectType currentInputType)) {
                break;
            }
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
        }
        // Reached the end and all fields were non-null
        return true;
    }
}
