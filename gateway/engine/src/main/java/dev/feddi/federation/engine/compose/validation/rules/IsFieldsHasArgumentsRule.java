package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import dev.feddi.federation.engine.parser.FieldSelectionMap.Alternative;
import dev.feddi.federation.engine.parser.FieldSelectionMap.ListSelection;
import dev.feddi.federation.engine.parser.FieldSelectionMap.ObjectField;
import dev.feddi.federation.engine.parser.FieldSelectionMap.ObjectSelection;
import dev.feddi.federation.engine.parser.FieldSelectionMap.Path;
import dev.feddi.federation.engine.parser.FieldSelectionMap.SelectedValue;
import dev.feddi.federation.engine.parser.FieldSelectionMapParser;
import dev.feddi.federation.engine.parser.InvalidSyntaxException;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLNamedType;
import org.jspecify.annotations.Nullable;

import java.util.List;

import static dev.feddi.federation.engine.compose.validation.SchemaElements.stringValue;
import static dev.feddi.federation.engine.compose.FederationDirectives.IS;

/**
 * Validates that {@code @is} selection maps select fields without arguments: a lookup's arguments are the stable
 * key of an entity, mapped to plain field paths. Only {@code @require} selection maps may supply arguments.
 *
 * Spec: https://graphql.github.io/composite-schemas-spec/draft/#sec-Is-Fields-Has-Arguments
 */
public final class IsFieldsHasArgumentsRule implements ValidationRule {

    private static final String CODE = "IS_FIELDS_HAS_ARGUMENTS";

    @Override
    public ValidationPhase phase() {
        return ValidationPhase.SOURCE_SCHEMA;
    }

    @Override
    public String name() {
        return "IsFieldsHasArgumentsRule";
    }

    @Override
    public ValidationResult validate(List<Subgraph> subgraphs) {
        ValidationResult.Builder builder = ValidationResult.builder();
        for (Subgraph subgraph : subgraphs) {
            for (GraphQLNamedType type : subgraph.schema().getAllTypesAsList()) {
                if (!(type instanceof GraphQLFieldsContainer container)) {
                    continue;
                }
                for (GraphQLFieldDefinition field : container.getFieldDefinitions()) {
                    for (GraphQLArgument argument : field.getArguments()) {
                        SelectedValue map = isSelectionMap(argument);
                        if (map != null && suppliesArguments(map)) {
                            String coordinate = String.format("%s.%s(%s:)", container.getName(), field.getName(),
                                argument.getName());
                            builder.addError(CODE, String.format(
                                "The @is directive on argument '%s' in schema '%s' selects a field with arguments; "
                                    + "@is selection maps must consist of plain field paths.",
                                coordinate, subgraph.name()), coordinate, subgraph.name(), IS);
                        }
                    }
                }
            }
        }
        return builder.build();
    }

    private static @Nullable SelectedValue isSelectionMap(GraphQLArgument argument) {
        if (!argument.hasAppliedDirective(IS)) {
            return null;
        }
        var field = argument.getAppliedDirective(IS).getArgument("field");
        String text = stringValue(field);
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return FieldSelectionMapParser.parseFieldSelectionMap(text);
        } catch (InvalidSyntaxException e) {
            return null; // FieldSelectionMapSyntaxRule reports it
        }
    }

    private static boolean suppliesArguments(SelectedValue value) {
        return value.alternatives().stream().anyMatch(IsFieldsHasArgumentsRule::suppliesArguments);
    }

    private static boolean suppliesArguments(Alternative alternative) {
        return switch (alternative) {
            case Path path -> path.segments().stream().anyMatch(segment -> segment.hasArguments());
            case ObjectSelection object -> (object.pathPrefix() != null && suppliesArguments(object.pathPrefix()))
                || object.fields().stream().map(ObjectField::value).anyMatch(IsFieldsHasArgumentsRule::suppliesArguments);
            case ListSelection list -> (list.pathPrefix() != null && suppliesArguments(list.pathPrefix()))
                || suppliesArguments(list.elementValue());
        };
    }
}
