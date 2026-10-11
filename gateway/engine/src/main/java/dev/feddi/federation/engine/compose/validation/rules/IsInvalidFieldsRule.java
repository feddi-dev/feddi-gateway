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
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;

import java.util.List;

import static dev.feddi.federation.engine.compose.FederationDirectives.IS;
import static dev.feddi.federation.engine.compose.validation.SchemaElements.stringValue;

/**
 * Validates that the fields an {@code @is} selection map selects exist: from the return type of the lookup field,
 * each field must be defined on the type in scope in one of the source schemas, with valid arguments, and each
 * type condition must name an existing type (Appendix A).
 *
 * <p>The spec resolves the map against the other schemas; feddi accepts fields from any schema, as for
 * LOOKUP_KEY_MISSING_FOR_TYPE.
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
        SourceSchemas schemas = SourceSchemas.of(subgraphs);
        SelectionMapWalker walker = new SelectionMapWalker(schemas);
        for (Subgraph subgraph : subgraphs) {
            for (GraphQLType type : subgraph.schema().getAllTypesAsList()) {
                if (!(type instanceof GraphQLFieldsContainer container)) {
                    continue;
                }
                for (GraphQLFieldDefinition field : container.getFieldDefinitions()) {
                    if (!(GraphQLTypeUtil.unwrapAll(field.getType()) instanceof GraphQLNamedType returnType)) {
                        continue;
                    }
                    for (GraphQLArgument argument : field.getArguments()) {
                        SelectedValue map = isSelectionMap(argument);
                        if (map != null) {
                            String coordinate = container.getName() + "." + field.getName() + "(" + argument.getName()
                                + ")";
                            walker.walk(map, returnType.getName(),
                                new Checks(schemas, coordinate, subgraph.name(), builder));
                        }
                    }
                }
            }
        }
        return builder.build();
    }

    private static SelectedValue isSelectionMap(GraphQLArgument argument) {
        if (!argument.hasAppliedDirective(IS)) {
            return null;
        }
        String text = stringValue(argument.getAppliedDirective(IS).getArgument("field"));
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return FieldSelectionMapParser.parseFieldSelectionMap(text);
        } catch (InvalidSyntaxException e) {
            return null; // FieldSelectionMapSyntaxRule reports it
        }
    }

    /** Reports the fields and type conditions of one {@code @is} map that do not exist. */
    private record Checks(SourceSchemas schemas, String coordinate, String schemaName,
                          ValidationResult.Builder builder) implements SelectionMapWalker.Visitor {

        @Override
        public boolean field(String parentType, PathSegment segment) {
            SourceSchemas.FieldIn field = schemas.field(parentType, segment.fieldName());
            if (field == null) {
                error(String.format("@is directive at '%s' in schema '%s' references field '%s' on type '%s' "
                    + "which does not exist in any schema.", coordinate, schemaName, segment.fieldName(), parentType));
                return false;
            }
            // The arguments the field is selected with (Appendix A, Path Field Argument Validity)
            for (String problem : FieldSelectionArguments.problems(parentType + "." + segment.fieldName(),
                    field.definition(), segment.arguments(), field.schema())) {
                error(String.format("@is directive at '%s' in schema '%s': %s.", coordinate, schemaName, problem));
            }
            return true;
        }

        @Override
        public boolean typeCondition(String scopeType, String condition, String inputPath) {
            if (!schemas.hasType(condition)) {
                error(String.format("@is directive at '%s' in schema '%s' references non-existent type '%s'.",
                    coordinate, schemaName, condition));
                return false;
            }
            return true;
        }

        private void error(String message) {
            builder.addError(CODE, message, coordinate, schemaName);
        }
    }
}
