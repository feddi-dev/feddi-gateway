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
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static dev.feddi.federation.engine.compose.FederationDirectives.IS;
import static dev.feddi.federation.engine.compose.FederationDirectives.LOOKUP;
import static dev.feddi.federation.engine.compose.FederationDirectives.REQUIRE;

/**
 * Validates that @lookup definitions are not redundant within a subgraph.
 *
 * Two @lookup fields are considered duplicates if they:
 * 1. Return the same type (after unwrapping)
 * 2. Have the same set of lookup argument paths (as determined by @is directives or implicit argument names)
 *
 * Additionally, a @lookup is considered redundant if its arguments are a superset of another @lookup
 * for the same return type. If one lookup can identify an entity with fewer arguments, adding more
 * arguments doesn't create a meaningfully different lookup.
 *
 * The important insight is that we compare the actual source fields (via @is), not the argument names,
 * because the semantics matter - two lookups that map to the same entity fields are equivalent.
 *
 * Examples of invalid duplicates (exact match):
 * - productById(id: String): Product @lookup
 *   productByKey(key: String @is(field: "id")): Product @lookup
 *   (Both map argument to "id" field on Product)
 *
 * Examples of invalid duplicates (subset/superset):
 * - productById(id: String): Product @lookup
 *   productByIdAndName(id: String, name: String): Product @lookup
 *   (The second is redundant because "id" alone already identifies the Product)
 */
public final class LookupDuplicateRule implements ValidationRule {

    private static final String CODE = "LOOKUP_DUPLICATE";

    @Override
    public ValidationPhase phase() {
        return ValidationPhase.SOURCE_SCHEMA;
    }

    @Override
    public String name() {
        return "LookupDuplicateRule";
    }

    @Override
    public ValidationResult validate(List<Subgraph> subgraphs) {
        ValidationResult.Builder builder = ValidationResult.builder();

        for (Subgraph subgraph : subgraphs) {
            validateSubgraph(subgraph, builder);
        }

        return builder.build();
    }

    private void validateSubgraph(Subgraph subgraph, ValidationResult.Builder builder) {
        GraphQLSchema schema = subgraph.schema();
        String schemaName = subgraph.name();

        // Collect all lookup fields in this subgraph
        List<LookupInfo> lookups = new ArrayList<>();

        for (GraphQLNamedType type : schema.getAllTypesAsList()) {
            if (type instanceof GraphQLObjectType objectType) {
                for (GraphQLFieldDefinition field : objectType.getFieldDefinitions()) {
                    if (field.hasAppliedDirective(LOOKUP)) {
                        LookupInfo info = extractLookupInfo(objectType, field);
                        if (info != null) {
                            lookups.add(info);
                        }
                    }
                }
            }
        }

        // Group lookups by (parentType, returnType) to compare their argument sets
        Map<TypePair, List<LookupInfo>> lookupsByType = new HashMap<>();
        for (LookupInfo lookup : lookups) {
            TypePair key = new TypePair(lookup.signature().parentTypeName(), lookup.signature().returnTypeName());
            lookupsByType
                .computeIfAbsent(key, k -> new ArrayList<>())
                .add(lookup);
        }

        // For each group, check for exact duplicates and subset relationships
        for (List<LookupInfo> group : lookupsByType.values()) {
            if (group.size() < 2) continue;

            // Check for exact duplicates (same argument set)
            Map<Set<String>, List<LookupInfo>> byExactArgs = new HashMap<>();
            for (LookupInfo lookup : group) {
                byExactArgs
                    .computeIfAbsent(lookup.signature().lookupArgPaths(), k -> new ArrayList<>())
                    .add(lookup);
            }

            for (List<LookupInfo> exactDuplicates : byExactArgs.values()) {
                if (exactDuplicates.size() > 1) {
                    reportExactDuplicates(exactDuplicates, schemaName, builder);
                }
            }

            // Check for subset relationships (one lookup's args are a proper subset of another's)
            // Sort by argument count so we compare smaller sets against larger sets
            List<LookupInfo> sorted = new ArrayList<>(group);
            sorted.sort((a, b) -> Integer.compare(
                a.signature().lookupArgPaths().size(),
                b.signature().lookupArgPaths().size()));

            for (int i = 0; i < sorted.size(); i++) {
                LookupInfo smaller = sorted.get(i);
                Set<String> smallerArgs = smaller.signature().lookupArgPaths();

                for (int j = i + 1; j < sorted.size(); j++) {
                    LookupInfo larger = sorted.get(j);
                    Set<String> largerArgs = larger.signature().lookupArgPaths();

                    // Skip if same size (would be exact duplicate, already handled)
                    if (smallerArgs.size() == largerArgs.size()) continue;

                    // Check if smaller is a proper subset of larger
                    if (largerArgs.containsAll(smallerArgs)) {
                        reportSubsetDuplicate(smaller, larger, schemaName, builder);
                    }
                }
            }
        }
    }

    /**
     * Key for grouping lookups by parent type and return type.
     */
    private record TypePair(String parentTypeName, String returnTypeName) {}

    private LookupInfo extractLookupInfo(GraphQLObjectType parentType, GraphQLFieldDefinition field) {
        // Get return type name
        GraphQLType returnType = GraphQLTypeUtil.unwrapAll(field.getType());
        if (!(returnType instanceof GraphQLNamedType namedType)) {
            return null;
        }
        String returnTypeName = namedType.getName();

        // Extract lookup argument paths from arguments
        Set<String> lookupArgPaths = new TreeSet<>(); // TreeSet for consistent ordering

        for (GraphQLArgument arg : field.getArguments()) {
            // Skip @require arguments - they're not part of the lookup signature
            if (arg.hasAppliedDirective(REQUIRE)) {
                continue;
            }

            String fieldPath;
            if (arg.hasAppliedDirective(IS)) {
                // Explicit @is mapping
                GraphQLAppliedDirective isDirective = arg.getAppliedDirective(IS);
                GraphQLAppliedDirectiveArgument fieldArg = isDirective.getArgument("field");
                fieldPath = extractStringValue(fieldArg);
            } else {
                // Implicit mapping - argument name equals field path
                fieldPath = arg.getName();
            }

            if (fieldPath != null && !fieldPath.isBlank()) {
                lookupArgPaths.add(fieldPath);
            }
        }

        String coordinate = parentType.getName() + "." + field.getName();
        LookupSignature signature = new LookupSignature(parentType.getName(), returnTypeName, lookupArgPaths);
        return new LookupInfo(coordinate, field.getName(), signature);
    }

    private void reportExactDuplicates(List<LookupInfo> duplicates, String schemaName,
                                       ValidationResult.Builder builder) {
        LookupSignature signature = duplicates.get(0).signature();
        String fieldList = duplicates.stream()
            .map(LookupInfo::coordinate)
            .collect(Collectors.joining(", "));

        String lookupArgsStr = signature.lookupArgPaths().isEmpty()
            ? "(no lookup arguments)"
            : String.join(", ", signature.lookupArgPaths());

        String message = String.format(
            "Duplicate @lookup definitions in schema '%s' on type '%s': [%s] all resolve type '%s' " +
            "using the same lookup arguments [%s]. " +
            "Each combination of return type and lookup arguments must be unique within a type.",
            schemaName, signature.parentTypeName(), fieldList, signature.returnTypeName(), lookupArgsStr
        );

        // Use first duplicate's coordinate for the error
        builder.addError(CODE, message, duplicates.get(0).coordinate(), schemaName, LOOKUP);
    }

    private void reportSubsetDuplicate(LookupInfo smaller, LookupInfo larger, String schemaName,
                                       ValidationResult.Builder builder) {
        String smallerArgsStr = String.join(", ", smaller.signature().lookupArgPaths());
        String largerArgsStr = String.join(", ", larger.signature().lookupArgPaths());

        String message = String.format(
            "Redundant @lookup definition in schema '%s': '%s' with arguments [%s] is redundant " +
            "because '%s' already identifies type '%s' with a subset of arguments [%s]. " +
            "Adding more arguments to a lookup doesn't create a meaningfully different lookup.",
            schemaName, larger.coordinate(), largerArgsStr,
            smaller.coordinate(), larger.signature().returnTypeName(), smallerArgsStr
        );

        // Report error on the larger (redundant) lookup
        builder.addError(CODE, message, larger.coordinate(), schemaName, LOOKUP);
    }

    private String extractStringValue(GraphQLAppliedDirectiveArgument arg) {
        if (arg == null) return null;
        Object value = arg.getValue();
        if (value instanceof StringValue stringValue) {
            return stringValue.getValue();
        }
        return value != null ? value.toString() : null;
    }

    /**
     * The signature that uniquely identifies a lookup's semantics within a parent type.
     */
    private record LookupSignature(String parentTypeName, String returnTypeName, Set<String> lookupArgPaths) {}

    /**
     * Information about a @lookup field.
     */
    private record LookupInfo(String coordinate, String fieldName, LookupSignature signature) {}
}
