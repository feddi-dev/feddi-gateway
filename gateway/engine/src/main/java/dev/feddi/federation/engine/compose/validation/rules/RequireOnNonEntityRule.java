package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.ValidationPhase;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.compose.validation.ValidationRule;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedOutputType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnionType;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static dev.feddi.federation.engine.compose.FederationDirectives.LOOKUP;
import static dev.feddi.federation.engine.compose.FederationDirectives.REQUIRE;

/**
 * Validates that @require directive is only used on fields of entity types.
 *
 * Entity types are types that are returned by @lookup fields. The @require directive
 * is used to declare data dependencies for field resolution, which only makes sense
 * in the context of entity resolution across subgraphs.
 *
 * Using @require on non-entity type fields is invalid because:
 * - @require declares dependencies on data from other schemas
 * - Only entity types can be resolved across subgraph boundaries
 * - Non-entity type fields cannot participate in cross-subgraph resolution
 *
 * Error Code: REQUIRE_ON_NON_ENTITY
 */
public final class RequireOnNonEntityRule implements ValidationRule {

    private static final String CODE = "REQUIRE_ON_NON_ENTITY";

    @Override
    public ValidationPhase phase() {
        // PRE_MERGE because we need to check entity types across all subgraphs
        return ValidationPhase.PRE_MERGE;
    }

    @Override
    public String name() {
        return "RequireOnNonEntityRule";
    }

    @Override
    public ValidationResult validate(List<Subgraph> subgraphs) {
        ValidationResult.Builder builder = ValidationResult.builder();

        // First, collect all entity type names (types returned by @lookup fields)
        Set<String> entityTypes = collectEntityTypes(subgraphs);

        // Then validate that @require is only used on entity type fields
        for (Subgraph subgraph : subgraphs) {
            validateSubgraph(subgraph, entityTypes, builder);
        }

        return builder.build();
    }

    /**
     * Collects all entity type names from all subgraphs.
     * An entity type is any type that is returned by a @lookup field.
     */
    private Set<String> collectEntityTypes(List<Subgraph> subgraphs) {
        Set<String> entityTypes = new HashSet<>();

        for (Subgraph subgraph : subgraphs) {
            GraphQLSchema schema = subgraph.schema();

            for (GraphQLNamedType type : schema.getAllTypesAsList()) {
                if (type instanceof GraphQLObjectType objectType) {
                    collectEntityTypesFromFields(objectType.getFieldDefinitions(), entityTypes, schema);
                } else if (type instanceof GraphQLInterfaceType interfaceType) {
                    collectEntityTypesFromFields(interfaceType.getFieldDefinitions(), entityTypes, schema);
                }
            }
        }

        return entityTypes;
    }

    private void collectEntityTypesFromFields(List<GraphQLFieldDefinition> fields,
                                              Set<String> entityTypes, GraphQLSchema schema) {
        for (GraphQLFieldDefinition field : fields) {
            if (field.hasAppliedDirective(LOOKUP)) {
                GraphQLOutputType returnType = field.getType();
                GraphQLNamedOutputType namedType = (GraphQLNamedOutputType) GraphQLTypeUtil.unwrapAll(returnType);
                entityTypes.add(namedType.getName());

                // When @lookup returns a union, all member types are entities
                if (namedType instanceof GraphQLUnionType unionType) {
                    for (GraphQLNamedOutputType member : unionType.getTypes()) {
                        entityTypes.add(member.getName());
                    }
                }

                // When @lookup returns an interface, implementing types are also entities
                if (namedType instanceof GraphQLInterfaceType interfaceType) {
                    for (GraphQLObjectType impl : schema.getImplementations(interfaceType)) {
                        entityTypes.add(impl.getName());
                    }
                }
            }
        }
    }

    private void validateSubgraph(Subgraph subgraph, Set<String> entityTypes, ValidationResult.Builder builder) {
        GraphQLSchema schema = subgraph.schema();

        for (GraphQLNamedType type : schema.getAllTypesAsList()) {
            if (type instanceof GraphQLObjectType objectType) {
                validateFieldsContainer(objectType.getName(), objectType.getFieldDefinitions(),
                    entityTypes, subgraph.name(), builder);
            } else if (type instanceof GraphQLInterfaceType interfaceType) {
                validateFieldsContainer(interfaceType.getName(), interfaceType.getFieldDefinitions(),
                    entityTypes, subgraph.name(), builder);
            }
        }
    }

    private void validateFieldsContainer(String typeName, List<GraphQLFieldDefinition> fields,
                                         Set<String> entityTypes, String schemaName,
                                         ValidationResult.Builder builder) {
        // Skip if this type is not an entity type
        if (!entityTypes.contains(typeName)) {
            // Check if any field has @require - this would be invalid
            for (GraphQLFieldDefinition field : fields) {
                for (GraphQLArgument arg : field.getArguments()) {
                    if (arg.hasAppliedDirective(REQUIRE)) {
                        String coordinate = String.format("%s.%s(%s:)", typeName, field.getName(), arg.getName());
                        String message = String.format(
                            "The @require directive on argument '%s' in schema '%s' is invalid " +
                            "because '%s' is not an entity type. @require can only be used on fields " +
                            "of entity types (types that have @lookup fields defined in some schema).",
                            coordinate, schemaName, typeName
                        );
                        builder.addError(CODE, message, coordinate, schemaName, REQUIRE);
                    }
                }
            }
        }
    }
}
