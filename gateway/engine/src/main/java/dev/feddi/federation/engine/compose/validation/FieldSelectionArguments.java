package dev.feddi.federation.engine.compose.validation;

import dev.feddi.federation.engine.compose.FederationDirectives;
import graphql.GraphQLContext;
import graphql.language.Argument;
import graphql.language.AstPrinter;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLSchema;
import graphql.validation.ValidationUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Path Field Argument Validity (Appendix A): the arguments a FieldSelectionMap selects a field with must be defined
 * on the field, coerce to their types, and include every required argument without a default (except @require
 * arguments, which the executor supplies). Variables cannot occur: the FieldSelectionMap grammar has none.
 */
public final class FieldSelectionArguments {

    private FieldSelectionArguments() {
    }

    /**
     * Returns the problems with selecting {@code field} with {@code arguments}, as messages; empty if valid.
     *
     * @param schema the source schema that defines {@code field}, for the argument types
     */
    public static List<String> problems(String coordinate, GraphQLFieldDefinition field, List<Argument> arguments,
                                        GraphQLSchema schema) {
        List<String> problems = new ArrayList<>();
        ValidationUtil validation = new ValidationUtil();
        for (Argument argument : arguments) {
            GraphQLArgument definition = field.getArgument(argument.getName());
            if (definition == null) {
                problems.add("'" + coordinate + "' has no argument '" + argument.getName() + "'");
            } else if (!validation.isValidLiteralValue(argument.getValue(), definition.getType(), schema,
                    GraphQLContext.getDefault(), Locale.ENGLISH)) {
                problems.add("argument '" + argument.getName() + "' of '" + coordinate + "' has the invalid value "
                    + AstPrinter.printAstCompact(argument.getValue()));
            }
        }
        for (GraphQLArgument definition : field.getArguments()) {
            boolean required = definition.getType() instanceof GraphQLNonNull && !definition.hasSetDefaultValue()
                && !definition.hasAppliedDirective(FederationDirectives.REQUIRE);
            if (required && arguments.stream().noneMatch(a -> a.getName().equals(definition.getName()))) {
                problems.add("'" + coordinate + "' is selected without its required argument '"
                    + definition.getName() + "'");
            }
        }
        return problems;
    }
}
