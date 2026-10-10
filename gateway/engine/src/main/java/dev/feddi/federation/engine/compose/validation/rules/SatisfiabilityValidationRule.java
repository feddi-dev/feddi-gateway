package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.FederationDirectives;
import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.validation.PostGraphValidationRule;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import dev.feddi.federation.engine.graph.Graph;
import dev.feddi.federation.engine.parser.FieldSelectionMap.Alternative;
import dev.feddi.federation.engine.parser.FieldSelectionMap.ListSelection;
import dev.feddi.federation.engine.parser.FieldSelectionMap.ObjectField;
import dev.feddi.federation.engine.parser.FieldSelectionMap.ObjectSelection;
import dev.feddi.federation.engine.parser.FieldSelectionMap.Path;
import dev.feddi.federation.engine.parser.FieldSelectionMap.PathSegment;
import dev.feddi.federation.engine.parser.FieldSelectionMap.SelectedValue;
import dev.feddi.federation.engine.parser.FieldSelectionMapParser;
import dev.feddi.federation.engine.parser.InvalidSyntaxException;
import graphql.schema.GraphQLAppliedDirective;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Validate Satisfiability, as specified: every executable query path of the composite schema must have at least
 * one source schema that can resolve it ({@code PlanOptions(path, allSchemas)} is not empty).
 *
 * <p>Paths are walked from the root types with the set of source schemas that can resolve the path so far. A step
 * stays in a schema that defines the next field, or moves to another one that defines it when that schema is
 * reachable through a {@code @lookup} whose inputs are resolvable from the current schema
 * ({@code IsReachable}). Fields with {@code @require} arguments additionally need their requirements resolvable
 * from schemas other than their own ({@code ResolveRequirements}). {@code @external} fields are never candidates in
 * the schema that declares them, {@code @provides} is ignored, and a field overridden with
 * {@code @override(from:)} is not a candidate in the schema it was taken from.
 *
 * <p>The spec enumerates paths without repeating a (type, field) step; since a step only depends on the current
 * type and candidate set, each (type, candidates) state is visited once, which covers the same paths.
 *
 * <p>One deviation from the letter of the draft (2026-10-04): {@code ResolveRequirements} restricts the candidate
 * schemas to those other than the requiring one, and the draft passes that restricted set on to
 * {@code IsReachable}, so the inputs of a lookup (e.g. the {@code id} of the entity the path is on) could not come
 * from the requiring schema either. That makes almost every requirement unsatisfiable, which contradicts the
 * draft's own examples. Here the restriction applies to the required fields only; lookup inputs may come from
 * any schema, and a requirement of a field on a requirement's path only excludes that field's own schema.
 *
 * @see <a href="https://graphql.github.io/composite-schemas-spec/draft/#sec-Validate-Satisfiability">Validate
 *     Satisfiability</a>
 */
public final class SatisfiabilityValidationRule implements PostGraphValidationRule {

    private static final String CODE = "UNSATISFIABLE_QUERY_PATH";

    @Override
    public String name() {
        return "SatisfiabilityValidationRule";
    }

    @Override
    public ValidationResult validate(Graph graph, GraphQLSchema mergedSchema, List<Subgraph> subgraphs) {
        return new Check(mergedSchema, subgraphs).run();
    }

    /** One step of a path: a field on a type. */
    private record Step(String type, String field) {
        @Override
        public String toString() {
            return type + "." + field;
        }
    }

    private static final class Check {
        private final GraphQLSchema schema;
        private final Map<String, GraphQLSchema> schemas = new LinkedHashMap<>();
        private final Set<String> overridden = new HashSet<>();  // "schema|type|field"
        private final Set<String> visited = new HashSet<>();
        private final Set<String> reported = new HashSet<>();
        private final Map<String, Boolean> reachable = new HashMap<>();
        private final Set<String> reachableInProgress = new HashSet<>();
        private boolean cycleCut;
        private final ValidationResult.Builder result = ValidationResult.builder();

        Check(GraphQLSchema schema, List<Subgraph> subgraphs) {
            this.schema = schema;
            for (Subgraph subgraph : subgraphs) {
                schemas.put(subgraph.name(), subgraph.schema());
            }
            for (Subgraph subgraph : subgraphs) {
                for (GraphQLNamedType type : subgraph.schema().getAllTypesAsList()) {
                    if (type instanceof GraphQLFieldsContainer container) {
                        for (GraphQLFieldDefinition field : container.getFieldDefinitions()) {
                            GraphQLAppliedDirective override = field.getAppliedDirective(FederationDirectives.OVERRIDE);
                            if (override != null && override.getArgument("from").getValue() instanceof String from) {
                                overridden.add(from + "|" + type.getName() + "|" + field.getName());
                            }
                        }
                    }
                }
            }
        }

        ValidationResult run() {
            List<GraphQLObjectType> roots = new ArrayList<>();
            for (GraphQLObjectType root : new GraphQLObjectType[] {
                    schema.getQueryType(), schema.getMutationType(), schema.getSubscriptionType()}) {
                if (root != null) {
                    roots.add(root);
                }
            }
            for (GraphQLObjectType root : roots) {
                for (GraphQLFieldDefinition field : root.getFieldDefinitions()) {
                    if (field.getName().startsWith("__")) {
                        continue;
                    }
                    Step step = new Step(root.getName(), field.getName());
                    Set<String> options = new TreeSet<>();
                    for (String name : schemas.keySet()) {
                        if (isCandidate(name, step)) {
                            options.add(name);
                        }
                    }
                    List<Step> path = List.of(step);
                    if (options.isEmpty()) {
                        report(path);
                    } else {
                        visitReturnType(path, field.getType(), options);
                    }
                }
            }
            return result.build();
        }

        /** Walks into every field of the possible types of the field's return type. */
        private void visitReturnType(List<Step> path, GraphQLType fieldType, Set<String> options) {
            if (!(GraphQLTypeUtil.unwrapAll(fieldType) instanceof GraphQLNamedType returnType)) {
                return;
            }
            Step parent = path.get(path.size() - 1);
            for (GraphQLObjectType type : possibleTypes(returnType)) {
                // Only schemas whose field can return this type lead to it (a union member that only another
                // schema adds is no executable path from here)
                Set<String> typeOptions = new TreeSet<>();
                for (String option : options) {
                    if (canReturn(option, parent, type.getName())) {
                        typeOptions.add(option);
                    }
                }
                if (typeOptions.isEmpty() || !visited.add(type.getName() + "|" + typeOptions)) {
                    continue;
                }
                for (GraphQLFieldDefinition field : type.getFieldDefinitions()) {
                    if (field.getName().startsWith("__")) {
                        continue;
                    }
                    Step step = new Step(type.getName(), field.getName());
                    List<Step> extended = new ArrayList<>(path);
                    extended.add(step);
                    Set<String> next = refine(List.of(step), typeOptions, schemas.keySet());
                    if (next.isEmpty()) {
                        report(extended);
                    } else {
                        visitReturnType(extended, field.getType(), next);
                    }
                }
            }
        }

        /** Whether the step's field in {@code schemaName} can return an object of {@code typeName}. */
        private boolean canReturn(String schemaName, Step step, String typeName) {
            GraphQLSchema source = schemas.get(schemaName);
            GraphQLFieldDefinition field = field(source, step);
            if (field == null || !(GraphQLTypeUtil.unwrapAll(field.getType()) instanceof GraphQLNamedType returned)) {
                return true;  // e.g. a step reached through another schema's lookup
            }
            return returned.getName().equals(typeName)
                || possibleTypes(source, returned).stream().anyMatch(t -> t.getName().equals(typeName));
        }

        private void report(List<Step> path) {
            Step last = path.get(path.size() - 1);
            if (!reported.add(last.toString())) {
                return;
            }
            StringBuilder text = new StringBuilder(path.get(0).type());
            for (Step step : path) {
                text.append('.').append(step.field());
            }
            result.addError(CODE, "Field '" + last + "' cannot be resolved on the query path '" + text
                + "': no source schema can resolve it there.", last.toString(), null);
        }

        /** RefinePlanOptions: the schemas that can resolve the remaining steps, starting from {@code options}. */
        private Set<String> refine(List<Step> steps, Set<String> options, Set<String> allowed) {
            Set<String> current = options;
            for (Step step : steps) {
                Set<String> next = new TreeSet<>();
                for (String currentSchema : current) {
                    for (String candidate : allowed) {
                        if (next.contains(candidate) || !isCandidate(candidate, step)) {
                            continue;
                        }
                        if (!candidate.equals(currentSchema) && !isReachable(currentSchema, candidate, step.type(), allowed)) {
                            continue;
                        }
                        if (hasRequirements(candidate, step)
                            && !requirementsResolvable(currentSchema, candidate, step, allowed)) {
                            continue;
                        }
                        next.add(candidate);
                    }
                }
                if (next.isEmpty()) {
                    return next;
                }
                current = next;
            }
            return current;
        }

        /** Whether {@code schemaName} defines the step's field, not as @external, and it is not overridden away. */
        private boolean isCandidate(String schemaName, Step step) {
            GraphQLFieldDefinition field = field(schemas.get(schemaName), step);
            return field != null && !field.hasAppliedDirective(FederationDirectives.EXTERNAL)
                && !overridden.contains(schemaName + "|" + step.type() + "|" + step.field());
        }

        /** IsReachable: a lookup in {@code target} resolves {@code type} with inputs resolvable from {@code source}. */
        private boolean isReachable(String source, String target, String type, Set<String> allowed) {
            String key = source + "|" + target + "|" + type + "|" + allowed;
            Boolean known = reachable.get(key);
            if (known != null) {
                return known;
            }
            if (!reachableInProgress.add(key)) {
                cycleCut = true;
                return false;
            }
            boolean outerCycleCut = cycleCut;
            cycleCut = false;
            boolean result = false;
            for (GraphQLFieldDefinition lookup : lookups(target, type)) {
                for (List<List<Step>> pathSet : lookupPathSets(lookup, type)) {
                    // Lookup inputs may come from any schema (see the class comment)
                    if (pathSetResolvable(pathSet, source, schemas.keySet())) {
                        result = true;
                        break;
                    }
                }
                if (result) {
                    break;
                }
            }
            reachableInProgress.remove(key);
            if (result || !cycleCut) {
                reachable.put(key, result);  // a negative answer cut short by a cycle depends on the caller
            }
            cycleCut = outerCycleCut || cycleCut;
            return result;
        }

        /** ResolveRequirements: every @require argument resolvable from schemas other than {@code target}. */
        private boolean requirementsResolvable(String source, String target, Step step, Set<String> allowed) {
            Set<String> others = new TreeSet<>(schemas.keySet());  // each requirement only excludes its own schema
            others.remove(target);
            for (GraphQLArgument argument : field(schemas.get(target), step).getArguments()) {
                GraphQLAppliedDirective require = argument.getAppliedDirective(FederationDirectives.REQUIRE);
                if (require == null) {
                    continue;
                }
                boolean satisfied = false;
                for (List<List<Step>> pathSet : pathSets(stringArgument(require, "field"), step.type())) {
                    if (pathSetResolvable(pathSet, source, others)) {
                        satisfied = true;
                        break;
                    }
                }
                if (!satisfied) {
                    return false;
                }
            }
            return true;
        }

        private boolean pathSetResolvable(List<List<Step>> pathSet, String source, Set<String> candidates) {
            for (List<Step> path : pathSet) {
                if (refine(path, Set.of(source), candidates).isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        private boolean hasRequirements(String schemaName, Step step) {
            return field(schemas.get(schemaName), step).getArguments().stream()
                .anyMatch(argument -> argument.hasAppliedDirective(FederationDirectives.REQUIRE));
        }

        /** The @lookup fields of {@code schemaName} that resolve {@code type} (directly or as a possible type). */
        private List<GraphQLFieldDefinition> lookups(String schemaName, String type) {
            GraphQLSchema source = schemas.get(schemaName);
            List<GraphQLFieldDefinition> lookups = new ArrayList<>();
            List<GraphQLObjectType> holders = new ArrayList<>();
            holders.add(source.getQueryType());
            for (GraphQLNamedType named : source.getAllTypesAsList()) {
                if (named instanceof GraphQLObjectType object && !object.getName().startsWith("__")
                    && object != source.getQueryType()) {
                    holders.add(object);
                }
            }
            for (GraphQLObjectType holder : holders) {
                if (holder == null) {
                    continue;
                }
                for (GraphQLFieldDefinition field : holder.getFieldDefinitions()) {
                    if (!field.hasAppliedDirective(FederationDirectives.LOOKUP)
                        || !(GraphQLTypeUtil.unwrapAll(field.getType()) instanceof GraphQLNamedType returned)) {
                        continue;
                    }
                    if (returned.getName().equals(type)
                        || possibleTypes(source, returned).stream().anyMatch(t -> t.getName().equals(type))) {
                        lookups.add(field);
                    }
                }
            }
            return lookups;
        }

        /** LookupPathSets: the cartesian product of the arguments' path-set alternatives. */
        private List<List<List<Step>>> lookupPathSets(GraphQLFieldDefinition lookup, String rootType) {
            List<List<List<Step>>> pathSets = new ArrayList<>();
            pathSets.add(new ArrayList<>());
            for (GraphQLArgument argument : lookup.getArguments()) {
                GraphQLAppliedDirective is = argument.getAppliedDirective(FederationDirectives.IS);
                String map = is != null ? stringArgument(is, "field") : argument.getName();
                pathSets = product(pathSets, pathSets(map, rootType));
            }
            return pathSets;
        }

        /** ExtractPathSets: the alternatives of a FieldSelectionMap, each a conjunction of paths. */
        private List<List<List<Step>>> pathSets(String map, String rootType) {
            if (map == null) {
                return List.of();
            }
            try {
                return pathSets(FieldSelectionMapParser.parseFieldSelectionMap(map), rootType);
            } catch (InvalidSyntaxException e) {
                return List.of();  // reported by the syntax rules
            }
        }

        private List<List<List<Step>>> pathSets(SelectedValue value, String rootType) {
            List<List<List<Step>>> alternatives = new ArrayList<>();
            for (Alternative alternative : value.alternatives()) {
                alternatives.addAll(pathSets(alternative, rootType, List.of()));
            }
            return alternatives;
        }

        private List<List<List<Step>>> pathSets(Alternative alternative, String rootType, List<Step> prefix) {
            switch (alternative) {
                case Path path -> {
                    List<Step> steps = steps(path, rootType, prefix);
                    return steps == null ? List.of() : List.of(List.of(steps));
                }
                case ObjectSelection object -> {
                    List<Step> objectPrefix = prefix;
                    String type = rootType;
                    if (object.pathPrefix() != null) {
                        objectPrefix = steps(object.pathPrefix(), rootType, prefix);
                        if (objectPrefix == null) {
                            return List.of();
                        }
                        type = typeAfter(objectPrefix, rootType);
                    }
                    List<List<List<Step>>> sets = new ArrayList<>();
                    sets.add(new ArrayList<>());
                    for (ObjectField field : object.fields()) {
                        List<List<List<Step>>> fieldSets = new ArrayList<>();
                        for (Alternative fieldAlternative : field.value().alternatives()) {
                            fieldSets.addAll(pathSets(fieldAlternative, type, objectPrefix));
                        }
                        sets = product(sets, fieldSets);
                    }
                    return sets;
                }
                case ListSelection list -> {
                    List<Step> listPrefix = prefix;
                    String type = rootType;
                    if (list.pathPrefix() != null) {
                        listPrefix = steps(list.pathPrefix(), rootType, prefix);
                        if (listPrefix == null) {
                            return List.of();
                        }
                        type = typeAfter(listPrefix, rootType);
                    }
                    List<List<List<Step>>> sets = new ArrayList<>();
                    for (Alternative element : list.elementValue().alternatives()) {
                        sets.addAll(pathSets(element, type, listPrefix));
                    }
                    return sets;
                }
            }
        }

        /** The steps of {@code path} from {@code type}, after {@code prefix}; null if it does not apply to it. */
        private List<Step> steps(Path path, String type, List<Step> prefix) {
            String current = type;
            if (path.hasInitialTypeCondition() && !path.initialTypeCondition().equals(current)) {
                GraphQLNamedType declared = schema.getType(current) instanceof GraphQLNamedType named ? named : null;
                boolean narrows = declared != null && possibleTypes(declared).stream()
                    .anyMatch(t -> t.getName().equals(path.initialTypeCondition()));
                if (!narrows) {
                    return null;
                }
                current = path.initialTypeCondition();
            }
            List<Step> steps = new ArrayList<>(prefix);
            for (PathSegment segment : path.segments()) {
                steps.add(new Step(current, segment.fieldName()));
                current = segment.hasTypeCondition() ? segment.typeCondition() : fieldTypeName(current, segment.fieldName());
                if (current == null) {
                    return null;
                }
            }
            return steps;
        }

        private String typeAfter(List<Step> steps, String rootType) {
            if (steps.isEmpty()) {
                return rootType;
            }
            Step last = steps.get(steps.size() - 1);
            return fieldTypeName(last.type(), last.field());
        }

        /** The named return type of a field, from the composite schema or any source schema. */
        private String fieldTypeName(String type, String fieldName) {
            List<GraphQLSchema> lookIn = new ArrayList<>();
            lookIn.add(schema);
            lookIn.addAll(schemas.values());
            for (GraphQLSchema candidate : lookIn) {
                GraphQLFieldDefinition field = field(candidate, new Step(type, fieldName));
                if (field != null) {
                    return GraphQLTypeUtil.unwrapAll(field.getType()) instanceof GraphQLNamedType named
                        ? named.getName() : null;
                }
            }
            return null;
        }

        private static GraphQLFieldDefinition field(GraphQLSchema schema, Step step) {
            return schema != null && schema.getType(step.type()) instanceof GraphQLFieldsContainer container
                ? container.getFieldDefinition(step.field()) : null;
        }

        private List<GraphQLObjectType> possibleTypes(GraphQLNamedType type) {
            return possibleTypes(schema, type);
        }

        private static List<GraphQLObjectType> possibleTypes(GraphQLSchema schema, GraphQLNamedType type) {
            return switch (type) {
                case GraphQLObjectType object -> List.of(object);
                case GraphQLInterfaceType iface -> schema.getImplementations(iface);
                case GraphQLUnionType union -> union.getTypes().stream()
                    .filter(GraphQLObjectType.class::isInstance).map(GraphQLObjectType.class::cast).toList();
                default -> List.of();
            };
        }

        private static List<List<List<Step>>> product(List<List<List<Step>>> left, List<List<List<Step>>> right) {
            List<List<List<Step>>> combined = new ArrayList<>();
            for (List<List<Step>> a : left) {
                for (List<List<Step>> b : right) {
                    Set<List<Step>> union = new LinkedHashSet<>(a);
                    union.addAll(b);
                    combined.add(new ArrayList<>(union));
                }
            }
            return combined;
        }

        private static String stringArgument(GraphQLAppliedDirective directive, String name) {
            var argument = directive.getArgument(name);
            return argument != null && argument.getValue() instanceof String value ? value : null;
        }
    }
}
