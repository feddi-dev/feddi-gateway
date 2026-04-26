package dev.feddi.federation.engine.compose;

import graphql.language.Description;
import graphql.language.DirectiveDefinition;
import graphql.language.ScalarTypeDefinition;

import java.util.List;
import java.util.Set;

import static graphql.introspection.Introspection.DirectiveLocation.ARGUMENT_DEFINITION;
import static graphql.introspection.Introspection.DirectiveLocation.ENUM;
import static graphql.introspection.Introspection.DirectiveLocation.ENUM_VALUE;
import static graphql.introspection.Introspection.DirectiveLocation.FIELD_DEFINITION;
import static graphql.introspection.Introspection.DirectiveLocation.INPUT_FIELD_DEFINITION;
import static graphql.introspection.Introspection.DirectiveLocation.INPUT_OBJECT;
import static graphql.introspection.Introspection.DirectiveLocation.INTERFACE;
import static graphql.introspection.Introspection.DirectiveLocation.OBJECT;
import static graphql.introspection.Introspection.DirectiveLocation.SCALAR;
import static graphql.introspection.Introspection.DirectiveLocation.UNION;
import static graphql.language.DirectiveLocation.newDirectiveLocation;
import static graphql.language.InputValueDefinition.newInputValueDefinition;
import static graphql.language.NonNullType.newNonNullType;
import static graphql.language.TypeName.newTypeName;

/**
 * Single source of truth for all GraphQL Federation directive definitions.
 *
 * <p>This class provides:
 * <ul>
 *   <li>String constants for directive names</li>
 *   <li>Full {@link DirectiveDefinition} objects per the Composite Schemas spec</li>
 *   <li>Custom scalar type definitions used by directives</li>
 * </ul>
 *
 * @see <a href="https://graphql.github.io/composite-schemas-spec/draft/">Composite Schemas Spec</a>
 */
public final class FederationDirectives {

    private FederationDirectives() {
        // Utility class
    }

    // ========== Directive Names ==========

    // Lookup and key directives
    public static final String KEY = "key";
    public static final String LOOKUP = "lookup";

    // Field mapping directives
    public static final String IS = "is";
    public static final String REQUIRE = "require";
    public static final String PROVIDES = "provides";

    // Visibility directives
    public static final String INTERNAL = "internal";
    public static final String INACCESSIBLE = "inaccessible";
    public static final String EXTERNAL = "external";

    // Sharing directives
    public static final String SHAREABLE = "shareable";
    public static final String OVERRIDE = "override";

    /**
     * Set of all federation directive names.
     * Used for filtering/stripping federation directives from the supergraph.
     */
    public static final Set<String> ALL = Set.of(
        KEY, LOOKUP, IS, REQUIRE, PROVIDES,
        INTERNAL, INACCESSIBLE, EXTERNAL,
        SHAREABLE, OVERRIDE
    );

    // ========== Custom Scalar Types ==========

    private static final String FIELD_SELECTION_SET = "FieldSelectionSet";
    private static final String FIELD_SELECTION_MAP = "FieldSelectionMap";

    /**
     * Scalar type for field selection syntax used by @key and @provides.
     * Example values: "id", "id name", "id user { name }"
     */
    public static final ScalarTypeDefinition FIELD_SELECTION_SET_SCALAR = ScalarTypeDefinition.newScalarTypeDefinition()
            .name(FIELD_SELECTION_SET)
            .description(description("Field selection syntax for @key and @provides directives"))
            .build();

    /**
     * Scalar type for field mapping syntax used by @is and @require.
     * Example values: "field: $arg", "id: $productId"
     */
    public static final ScalarTypeDefinition FIELD_SELECTION_MAP_SCALAR = ScalarTypeDefinition.newScalarTypeDefinition()
            .name(FIELD_SELECTION_MAP)
            .description(description("Field mapping syntax for @is and @require directives"))
            .build();

    // ========== Directive Definitions ==========

    /**
     * @key(fields: FieldSelectionSet!) repeatable on OBJECT | INTERFACE
     *
     * <p>Optional directive that marks key fields as implicitly @shareable across subgraphs.
     * This is a convenience for declaring that the specified fields can be resolved by any
     * subgraph that defines them, without requiring explicit @shareable on each field.
     *
     * <p>Note: A type becomes an "entity" (resolvable across subgraphs) by having a @lookup
     * field that returns it, NOT by having a @key directive. The @key directive is optional.
     */
    public static final DirectiveDefinition KEY_DEFINITION = DirectiveDefinition.newDirectiveDefinition()
            .name(KEY)
            .description(description("Marks key fields as implicitly shareable across subgraphs"))
            .repeatable(true)
            .directiveLocation(newDirectiveLocation().name(OBJECT.name()).build())
            .directiveLocation(newDirectiveLocation().name(INTERFACE.name()).build())
            .inputValueDefinition(
                    newInputValueDefinition()
                            .name("fields")
                            .description(description("The fields to mark as shareable"))
                            .type(newNonNullType(newTypeName().name(FIELD_SELECTION_SET).build()).build())
                            .build())
            .build();

    /**
     * @lookup on FIELD_DEFINITION
     *
     * <p>Marks a field as a lookup resolver. The return type of a @lookup field becomes
     * an "entity" - a type that can be resolved across subgraphs. This is what defines
     * an entity, not the @key directive.
     */
    public static final DirectiveDefinition LOOKUP_DEFINITION = DirectiveDefinition.newDirectiveDefinition()
            .name(LOOKUP)
            .description(description("Marks a field as a lookup resolver, making its return type an entity"))
            .directiveLocation(newDirectiveLocation().name(FIELD_DEFINITION.name()).build())
            .build();

    /**
     * @is(field: FieldSelectionMap!) on ARGUMENT_DEFINITION
     *
     * <p>Maps a lookup argument to a source field on the entity being resolved.
     */
    public static final DirectiveDefinition IS_DEFINITION = DirectiveDefinition.newDirectiveDefinition()
            .name(IS)
            .description(description("Maps a lookup argument to a source field"))
            .directiveLocation(newDirectiveLocation().name(ARGUMENT_DEFINITION.name()).build())
            .inputValueDefinition(
                    newInputValueDefinition()
                            .name("field")
                            .description(description("The field mapping expression"))
                            .type(newNonNullType(newTypeName().name(FIELD_SELECTION_MAP).build()).build())
                            .build())
            .build();

    /**
     * @require(field: FieldSelectionMap!) on ARGUMENT_DEFINITION
     *
     * <p>Specifies that a field argument requires data from another field to be resolved first.
     */
    public static final DirectiveDefinition REQUIRE_DEFINITION = DirectiveDefinition.newDirectiveDefinition()
            .name(REQUIRE)
            .description(description("Specifies that an argument requires data from another field"))
            .directiveLocation(newDirectiveLocation().name(ARGUMENT_DEFINITION.name()).build())
            .inputValueDefinition(
                    newInputValueDefinition()
                            .name("field")
                            .description(description("The field mapping expression"))
                            .type(newNonNullType(newTypeName().name(FIELD_SELECTION_MAP).build()).build())
                            .build())
            .build();

    /**
     * @provides(fields: FieldSelectionSet!) on FIELD_DEFINITION
     *
     * <p>Specifies that a field provides additional fields on the returned type,
     * avoiding extra subgraph calls.
     */
    public static final DirectiveDefinition PROVIDES_DEFINITION = DirectiveDefinition.newDirectiveDefinition()
            .name(PROVIDES)
            .description(description("Specifies additional fields provided by this field's resolver"))
            .directiveLocation(newDirectiveLocation().name(FIELD_DEFINITION.name()).build())
            .inputValueDefinition(
                    newInputValueDefinition()
                            .name("fields")
                            .description(description("The fields that are provided"))
                            .type(newNonNullType(newTypeName().name(FIELD_SELECTION_SET).build()).build())
                            .build())
            .build();

    /**
     * @internal on OBJECT | FIELD_DEFINITION
     *
     * <p>Marks a type or field as internal, hiding it from the public API while keeping
     * it available for inter-subgraph communication.
     *
     * @see <a href="https://graphql.github.io/composite-schemas-spec/draft/#sec--internal">Spec</a>
     */
    public static final DirectiveDefinition INTERNAL_DEFINITION = DirectiveDefinition.newDirectiveDefinition()
            .name(INTERNAL)
            .description(description("Marks a type or field as internal (hidden from public API)"))
            .directiveLocation(newDirectiveLocation().name(OBJECT.name()).build())
            .directiveLocation(newDirectiveLocation().name(FIELD_DEFINITION.name()).build())
            .build();

    /**
     * @inaccessible on OBJECT | FIELD_DEFINITION | INTERFACE | UNION | ARGUMENT_DEFINITION |
     *               SCALAR | ENUM | ENUM_VALUE | INPUT_OBJECT | INPUT_FIELD_DEFINITION
     *
     * <p>Marks a type or field as inaccessible, completely removing it from the composed schema.
     */
    public static final DirectiveDefinition INACCESSIBLE_DEFINITION = DirectiveDefinition.newDirectiveDefinition()
            .name(INACCESSIBLE)
            .description(description("Marks a type or field as inaccessible (removed from composed schema)"))
            .directiveLocation(newDirectiveLocation().name(OBJECT.name()).build())
            .directiveLocation(newDirectiveLocation().name(FIELD_DEFINITION.name()).build())
            .directiveLocation(newDirectiveLocation().name(INTERFACE.name()).build())
            .directiveLocation(newDirectiveLocation().name(UNION.name()).build())
            .directiveLocation(newDirectiveLocation().name(ARGUMENT_DEFINITION.name()).build())
            .directiveLocation(newDirectiveLocation().name(SCALAR.name()).build())
            .directiveLocation(newDirectiveLocation().name(ENUM.name()).build())
            .directiveLocation(newDirectiveLocation().name(ENUM_VALUE.name()).build())
            .directiveLocation(newDirectiveLocation().name(INPUT_OBJECT.name()).build())
            .directiveLocation(newDirectiveLocation().name(INPUT_FIELD_DEFINITION.name()).build())
            .build();

    /**
     * @external on FIELD_DEFINITION
     *
     * <p>Marks a field as externally defined (defined in another subgraph).
     *
     * @see <a href="https://graphql.github.io/composite-schemas-spec/draft/#sec--external">Spec</a>
     */
    public static final DirectiveDefinition EXTERNAL_DEFINITION = DirectiveDefinition.newDirectiveDefinition()
            .name(EXTERNAL)
            .description(description("Marks a field as defined in another subgraph"))
            .directiveLocation(newDirectiveLocation().name(FIELD_DEFINITION.name()).build())
            .build();

    /**
     * @shareable repeatable on OBJECT | FIELD_DEFINITION
     *
     * <p>Marks a field as shareable, allowing multiple subgraphs to resolve it.
     */
    public static final DirectiveDefinition SHAREABLE_DEFINITION = DirectiveDefinition.newDirectiveDefinition()
            .name(SHAREABLE)
            .description(description("Marks a field as shareable across multiple subgraphs"))
            .repeatable(true)
            .directiveLocation(newDirectiveLocation().name(OBJECT.name()).build())
            .directiveLocation(newDirectiveLocation().name(FIELD_DEFINITION.name()).build())
            .build();

    /**
     * @override(from: String!) on FIELD_DEFINITION
     *
     * <p>Indicates that this subgraph takes over responsibility for a field from another subgraph.
     */
    public static final DirectiveDefinition OVERRIDE_DEFINITION = DirectiveDefinition.newDirectiveDefinition()
            .name(OVERRIDE)
            .description(description("Indicates this subgraph overrides a field from another subgraph"))
            .directiveLocation(newDirectiveLocation().name(FIELD_DEFINITION.name()).build())
            .inputValueDefinition(
                    newInputValueDefinition()
                            .name("from")
                            .description(description("The name of the subgraph being overridden"))
                            .type(newNonNullType(newTypeName().name("String").build()).build())
                            .build())
            .build();

    /**
     * All federation directive definitions.
     */
    public static final List<DirectiveDefinition> ALL_DEFINITIONS = List.of(
            KEY_DEFINITION,
            LOOKUP_DEFINITION,
            IS_DEFINITION,
            REQUIRE_DEFINITION,
            PROVIDES_DEFINITION,
            INTERNAL_DEFINITION,
            INACCESSIBLE_DEFINITION,
            EXTERNAL_DEFINITION,
            SHAREABLE_DEFINITION,
            OVERRIDE_DEFINITION
    );

    /**
     * All custom scalar type definitions used by federation directives.
     */
    public static final List<ScalarTypeDefinition> SCALAR_DEFINITIONS = List.of(
            FIELD_SELECTION_SET_SCALAR,
            FIELD_SELECTION_MAP_SCALAR
    );

    private static Description description(String text) {
        return new Description(text, null, false);
    }
}
