package dev.feddi.federation.engine.oracle;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.parser.FieldSelectionMap.Alternative;
import dev.feddi.federation.engine.parser.FieldSelectionMap.ListSelection;
import dev.feddi.federation.engine.parser.FieldSelectionMap.ObjectField;
import dev.feddi.federation.engine.parser.FieldSelectionMap.ObjectSelection;
import dev.feddi.federation.engine.parser.FieldSelectionMap.Path;
import dev.feddi.federation.engine.parser.FieldSelectionMap.PathSegment;
import dev.feddi.federation.engine.parser.FieldSelectionMap.SelectedValue;
import dev.feddi.federation.engine.parser.FieldSelectionMapParser;
import dev.feddi.federation.engine.parser.InvalidSyntaxException;
import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.ArrayValue;
import graphql.language.BooleanValue;
import graphql.language.EnumValue;
import graphql.language.FloatValue;
import graphql.language.IntValue;
import graphql.language.NullValue;
import graphql.language.ObjectValue;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLAppliedDirectiveArgument;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLCodeRegistry;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLEnumValueDefinition;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnionType;
import graphql.schema.TypeResolver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic data for executable copies of source schemas and of the composite schema, so a
 * query's result through feddi can be compared with the same query against a monolith.
 *
 * <p>Every object has a numeric seed. Key fields (fields of a {@code @key}, and fields that lookup
 * arguments map to, across all source schemas) encode it: {@code "s123"} for strings and IDs,
 * {@code 123} for numbers, so a lookup by a key recovers the seed. Other ID fields look different
 * ({@code "i123"}), so only key values are taken for seeds. Every other value derives from
 * (type, seed, field, arguments) only, so all schemas that offer a field return the same value for
 * the same entity. Arguments annotated with {@code @require} are left out: they carry data of the
 * same entity and do not exist in the composite schema. Lists have two items; a list field whose
 * arguments carry keys returns one entity per key. No value is null.
 */
public final class DeterministicData {

    private static final int LIST_SIZE = 2;
    private static final Pattern SEED = Pattern.compile("s(\\d{6,10})\\b|\\b(\\d{6,10})\\b");

    private final Map<String, Set<String>> keyFields = new HashMap<>();
    private final Set<String> requireArguments = new HashSet<>();
    private final Map<String, List<String>> compositePossibleTypes = new HashMap<>();
    private final List<String> objectTypes = new ArrayList<>();
    private final Map<String, List<String>> fieldPossibleTypes = new HashMap<>();
    private final Map<String, GraphQLFieldDefinition> definitions = new HashMap<>();  // "Type.field", not @external
    private final Map<String, GraphQLSchema> definingSchemas = new HashMap<>();
    private final Map<String, Set<String>> abstractMembers = new HashMap<>();

    public DeterministicData(List<Subgraph> subgraphs, GraphQLSchema compositeSchema) {
        for (Subgraph subgraph : subgraphs) {
            collect(subgraph.schema());
        }
        for (Subgraph subgraph : subgraphs) {
            for (GraphQLNamedType type : subgraph.schema().getAllTypesAsList()) {
                if (type instanceof GraphQLInterfaceType || type instanceof GraphQLUnionType) {
                    abstractMembers.computeIfAbsent(type.getName(), k -> new HashSet<>())
                        .addAll(possibleTypes(subgraph.schema(), type));
                }
                if (type instanceof GraphQLObjectType object && !type.getName().startsWith("__")) {
                    for (GraphQLFieldDefinition field : object.getFieldDefinitions()) {
                        if (!field.hasAppliedDirective("external")) {
                            definitions.putIfAbsent(type.getName() + "." + field.getName(), field);
                            definingSchemas.putIfAbsent(type.getName() + "." + field.getName(), subgraph.schema());
                        }
                    }
                }
            }
        }
        // Per abstract field: the types every schema that defines the field can return (a's "feed: [Post]" can only
        // return a's Post implementations, even if another schema adds more)
        for (Subgraph subgraph : subgraphs) {
            for (GraphQLNamedType type : subgraph.schema().getAllTypesAsList()) {
                if (!(type instanceof GraphQLFieldsContainer container) || type.getName().startsWith("__")) {
                    continue;
                }
                for (GraphQLFieldDefinition field : container.getFieldDefinitions()) {
                    GraphQLNamedType returned = GraphQLTypeUtil.unwrapAllAs(field.getType());
                    if (returned instanceof GraphQLInterfaceType || returned instanceof GraphQLUnionType) {
                        List<String> local = possibleTypes(subgraph.schema(), returned);
                        fieldPossibleTypes.merge(type.getName() + "." + field.getName(), local, (a, b) -> {
                            List<String> common = new ArrayList<>(a);
                            common.retainAll(b);
                            return common;
                        });
                    }
                }
            }
        }
        for (GraphQLNamedType type : compositeSchema.getAllTypesAsList()) {
            if (type instanceof GraphQLObjectType && !type.getName().startsWith("__")) {
                objectTypes.add(type.getName());
            }
            if (type instanceof GraphQLInterfaceType || type instanceof GraphQLUnionType) {
                // Types every source schema with this abstract type can return, so that whichever
                // schema resolves a field, it picks the same concrete type as the monolith.
                List<String> common = new ArrayList<>(possibleTypes(compositeSchema, type));
                for (Subgraph subgraph : subgraphs) {
                    GraphQLType local = subgraph.schema().getType(type.getName());
                    if (local != null) {
                        common.retainAll(possibleTypes(subgraph.schema(), local));
                    }
                }
                compositePossibleTypes.put(type.getName(), common.isEmpty() ? possibleTypes(compositeSchema, type) : common);
            }
        }
    }

    /** An executable copy of the schema that serves this data. */
    public GraphQLSchema executable(GraphQLSchema schema) {
        GraphQLCodeRegistry.Builder registry = GraphQLCodeRegistry.newCodeRegistry()
            .defaultDataFetcher(env -> (DataFetcher<Object>) this::resolve);
        TypeResolver typeResolver = env -> env.getSchema().getObjectType(
            String.valueOf(((Map<?, ?>) env.getObject()).get("__type")));
        for (GraphQLNamedType type : schema.getAllTypesAsList()) {
            if (type instanceof GraphQLInterfaceType interfaceType) {
                registry.typeResolver(interfaceType, typeResolver);
            } else if (type instanceof GraphQLUnionType unionType) {
                registry.typeResolver(unionType, typeResolver);
            }
        }
        return schema.transform(b -> b.codeRegistry(registry.build()));
    }

    private Object resolve(DataFetchingEnvironment env) {
        Map<?, ?> source = env.getSource() instanceof Map<?, ?> map ? map : Map.of();
        long parentSeed = source.get("__seed") instanceof Long seed ? seed : 0L;
        String parentType = ((GraphQLNamedType) env.getParentType()).getName();
        String field = env.getField().getName();
        checkRequirements(env, parentType, source);
        String args = canonical(effectiveArguments(env, parentType, field));
        return valueOf(env.getGraphQLSchema(), env.getFieldType(), parentType, field, parentSeed, args);
    }

    /** The value of a field of the object (parentType, parentSeed) with the given (canonical) arguments. */
    private Object valueOf(GraphQLSchema schema, GraphQLOutputType type, String parentType, String field,
                           long parentSeed, String args) {
        GraphQLNamedType named = GraphQLTypeUtil.unwrapAllAs(type);
        boolean list = GraphQLTypeUtil.unwrapNonNull(type) instanceof GraphQLList;
        String base = parentType + "." + field + "|" + parentSeed + "|" + args;

        if (named instanceof GraphQLFieldsContainer || named instanceof GraphQLUnionType) {
            List<Long> argSeeds = seedsIn(args);
            if (list) {
                List<Object> items = new ArrayList<>();
                if (!argSeeds.isEmpty()) {
                    argSeeds.forEach(seed -> items.add(entity(schema, named, seed, true, parentType + "." + field)));
                } else {
                    for (int i = 0; i < LIST_SIZE; i++) {
                        items.add(entity(schema, named, hash(base + "|" + i), false, parentType + "." + field));
                    }
                }
                return items;
            }
            return argSeeds.isEmpty() ? entity(schema, named, hash(base), false, parentType + "." + field)
                : entity(schema, named, argSeeds.get(0), true, parentType + "." + field);
        }
        return scalars(GraphQLTypeUtil.unwrapNonNull(type), named, parentType, field, parentSeed, args, 0);
    }

    // ---- @require arguments: the value the gateway passes must be the required data of the same object ----

    /**
     * Fails the field when a @require argument does not carry the value its FieldSelectionMap selects from this
     * object's data, so that the oracle also sees wrong requirement values (arguments, aliases, variables).
     */
    private void checkRequirements(DataFetchingEnvironment env, String parentType, Map<?, ?> source) {
        for (GraphQLArgument argument : env.getFieldDefinition().getArguments()) {
            GraphQLAppliedDirective require = argument.getAppliedDirective("require");
            if (require == null || !(source.get("__seed") instanceof Long)) {
                continue;
            }
            Object expected;
            try {
                expected = evaluate(FieldSelectionMapParser.parseFieldSelectionMap(stringArgument(require, "field")),
                    source);
            } catch (InvalidSyntaxException e) {
                throw new IllegalStateException(e);
            }
            Object actual = env.getArgument(argument.getName());
            if (!canonical(expected).equals(canonical(actual))) {
                throw new IllegalStateException("requirement " + parentType + "." + env.getField().getName() + "("
                    + argument.getName() + ") got " + canonical(actual) + ", expected " + canonical(expected));
            }
        }
    }

    private Object evaluate(SelectedValue value, Object object) {
        for (Alternative alternative : value.alternatives()) {
            Object result = switch (alternative) {
                case Path path -> evaluatePath(path, object);
                case ObjectSelection selection -> {
                    Object target = selection.pathPrefix() == null ? object : evaluatePath(selection.pathPrefix(), object);
                    if (!(target instanceof Map<?, ?>)) {
                        yield null;
                    }
                    Map<String, Object> fields = new LinkedHashMap<>();
                    for (ObjectField field : selection.fields()) {
                        fields.put(field.name(), evaluate(field.value(), target));
                    }
                    yield fields;
                }
                case ListSelection selection -> {
                    Object items = selection.pathPrefix() == null ? object : evaluatePath(selection.pathPrefix(), object);
                    yield items instanceof List<?> list
                        ? list.stream().map(item -> evaluate(selection.elementValue(), item)).toList() : null;
                }
            };
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private Object evaluatePath(Path path, Object object) {
        if (path.hasInitialTypeCondition() && !hasType(object, path.initialTypeCondition())) {
            return null;
        }
        Object current = object;
        for (PathSegment segment : path.segments()) {
            current = fieldOf(current, segment);
            if (segment.hasTypeCondition()) {
                current = current instanceof List<?> list
                    ? list.stream().filter(item -> hasType(item, segment.typeCondition())).toList()
                    : hasType(current, segment.typeCondition()) ? current : null;
            }
        }
        return current;
    }

    /** The value of a segment's field on an object (or on each item of a list of objects). */
    private Object fieldOf(Object object, PathSegment segment) {
        if (object instanceof List<?> list) {
            return list.stream().map(item -> fieldOf(item, segment)).toList();
        }
        if (!(object instanceof Map<?, ?> map) || !(map.get("__seed") instanceof Long seed)) {
            return null;
        }
        String type = String.valueOf(map.get("__type"));
        String key = type + "." + segment.fieldName();
        GraphQLFieldDefinition definition = definitions.get(key);
        if (definition == null) {
            return null;
        }
        Map<String, Object> args = new TreeMap<>();
        for (GraphQLArgument argument : definition.getArguments()) {
            if (argument.hasSetDefaultValue() && argument.getArgumentDefaultValue().getValue() instanceof Value<?> literal) {
                args.put(argument.getName(), literalValue(literal, argument.getType()));
            }
        }
        for (graphql.language.Argument argument : segment.arguments()) {
            GraphQLArgument argumentDefinition = definition.getArgument(argument.getName());
            args.put(argument.getName(), literalValue(argument.getValue(),
                argumentDefinition != null ? argumentDefinition.getType() : null));
        }
        args.keySet().removeIf(arg -> requireArguments.contains(key + "." + arg));
        return valueOf(definingSchemas.get(key), definition.getType(), type, segment.fieldName(), seed, canonical(args));
    }

    private boolean hasType(Object object, String typeCondition) {
        if (!(object instanceof Map<?, ?> map)) {
            return false;
        }
        String type = String.valueOf(map.get("__type"));
        return type.equals(typeCondition) || compositePossibleTypes.getOrDefault(typeCondition, List.of()).contains(type)
            || abstractMembers.getOrDefault(typeCondition, Set.of()).contains(type);
    }

    /** A literal as graphql-java coerces an argument value of {@code type}. */
    private static Object literalValue(Value<?> literal, GraphQLInputType type) {
        GraphQLType unwrapped = type == null ? null : GraphQLTypeUtil.unwrapNonNull(type);
        return switch (literal) {
            case NullValue ignored -> null;
            case ArrayValue array -> array.getValues().stream()
                .map(item -> literalValue(item, unwrapped instanceof GraphQLList list
                    ? (GraphQLInputType) list.getWrappedType() : null))
                .toList();
            case ObjectValue object -> {
                Map<String, Object> fields = new LinkedHashMap<>();
                for (graphql.language.ObjectField field : object.getObjectFields()) {
                    GraphQLInputType fieldType = unwrapped instanceof GraphQLInputObjectType input
                        && input.getField(field.getName()) != null ? input.getField(field.getName()).getType() : null;
                    fields.put(field.getName(), literalValue(field.getValue(), fieldType));
                }
                yield fields;
            }
            default -> unwrapped instanceof GraphQLScalarType scalar
                ? scalar.getCoercing().parseLiteral(literal, CoercedVariables.emptyVariables(), GraphQLContext.getDefault(),
                    Locale.ENGLISH)
                : literal instanceof EnumValue enumValue ? enumValue.getName()
                : literal instanceof StringValue string ? string.getValue()
                : literal instanceof BooleanValue bool ? bool.isValue()
                : literal instanceof IntValue integer ? integer.getValue().intValue()
                : literal instanceof FloatValue decimal ? decimal.getValue().doubleValue()
                : null;
        };
    }

    /** A scalar or enum value, or (nested) lists of them, with one index per position. */
    private Object scalars(GraphQLType type, GraphQLNamedType named, String parentType, String field, long seed,
                           String args, int index) {
        if (type instanceof GraphQLList listType) {
            List<Object> items = new ArrayList<>();
            for (int i = 0; i < LIST_SIZE; i++) {
                items.add(scalars(GraphQLTypeUtil.unwrapNonNull(listType.getWrappedType()), named, parentType, field,
                    seed, args, index * LIST_SIZE + i));
            }
            return items;
        }
        return scalar(named, parentType, field, seed, args, index);
    }

    /**
     * An object of {@code type} (its concrete type, for an interface or union). A seed from a key keeps the concrete
     * type its object was created with, like a global ID does; a new object's seed is aligned to its concrete type.
     */
    private Map<String, Object> entity(GraphQLSchema schema, GraphQLNamedType type, long seed, boolean fromKey,
                                       String fieldKey) {
        String concrete = type.getName();
        if (type instanceof GraphQLInterfaceType || type instanceof GraphQLUnionType) {
            // Choose from the types all schemas with this field can return, so source schemas and the monolith agree.
            List<String> local = possibleTypes(schema, type);
            List<String> fieldTypes = fieldPossibleTypes.get(fieldKey);
            List<String> candidates = fieldTypes != null && !fieldTypes.isEmpty() ? fieldTypes
                : compositePossibleTypes.getOrDefault(type.getName(), local);
            String encoded = fromKey && !objectTypes.isEmpty() ? objectTypes.get((int) (seed % objectTypes.size())) : null;
            String chosen = local.contains(encoded) ? encoded
                : candidates.isEmpty() ? null : candidates.get((int) (seed % candidates.size()));
            concrete = local.contains(chosen) ? chosen : local.isEmpty() ? concrete : local.get(0);
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("__type", concrete);
        value.put("__seed", fromKey ? seed : alignedSeed(seed, concrete));
        return value;
    }

    /** The seed next to {@code seed} that encodes {@code type} ({@code seed % objectTypes == index of type}). */
    private long alignedSeed(long seed, String type) {
        int index = objectTypes.indexOf(type);
        if (index < 0) {
            return seed;
        }
        long aligned = seed - seed % objectTypes.size() + index;
        return aligned < 100000L ? aligned + objectTypes.size() : aligned;
    }

    private Object scalar(GraphQLNamedType type, String parentType, String field, long seed, String args, int index) {
        boolean key = keyFields.getOrDefault(parentType, Set.of()).contains(field);
        long h = key ? seed : hash(parentType + "." + field + "|" + seed + "|" + args + "|" + index);
        if (type instanceof GraphQLEnumType enumType) {
            List<String> values = enumType.getValues().stream().map(GraphQLEnumValueDefinition::getName).sorted().toList();
            return values.get((int) (h % values.size()));
        }
        return switch (type.getName()) {
            case "ID" -> (key ? "s" : "i") + h;
            case "String" -> key ? "s" + h : field + "-" + (h % 10000);
            case "Boolean" -> h % 2 == 0;
            case "Float" -> (h % 100000) / 100.0;
            case "Int" -> key ? (int) h : (int) (h % 10000);
            default -> key ? "s" + h : h % 100000;
        };
    }

    private Map<String, Object> effectiveArguments(DataFetchingEnvironment env, String parentType, String field) {
        Map<String, Object> args = new TreeMap<>(env.getArguments());
        args.keySet().removeIf(arg -> requireArguments.contains(parentType + "." + field + "." + arg));
        return args;
    }

    private void collect(GraphQLSchema schema) {
        for (GraphQLNamedType type : schema.getAllTypesAsList()) {
            if (type.getName().startsWith("__")) {
                continue;
            }
            if (type instanceof GraphQLObjectType objectType) {
                for (GraphQLAppliedDirective key : objectType.getAppliedDirectives("key")) {
                    String fields = stringArgument(key, "fields");
                    if (fields != null) {
                        topLevelNames(fields).forEach(f -> keyFields.computeIfAbsent(type.getName(), k -> new HashSet<>()).add(f));
                    }
                }
            }
            if (!(type instanceof GraphQLFieldsContainer container)) {
                continue;
            }
            for (GraphQLFieldDefinition field : container.getFieldDefinitions()) {
                for (GraphQLArgument argument : field.getArguments()) {
                    if (argument.hasAppliedDirective("require")) {
                        requireArguments.add(type.getName() + "." + field.getName() + "." + argument.getName());
                    }
                }
                if (!field.hasAppliedDirective("lookup")) {
                    continue;
                }
                GraphQLNamedType target = GraphQLTypeUtil.unwrapAllAs(field.getType());
                List<String> targets = target instanceof GraphQLObjectType ? List.of(target.getName()) : possibleTypes(schema, target);
                for (GraphQLArgument argument : field.getArguments()) {
                    String mapped = argument.hasAppliedDirective("is") ? stringArgument(argument.getAppliedDirective("is"), "field") : null;
                    if (mapped == null) {
                        targets.forEach(t -> keyFields.computeIfAbsent(t, k -> new HashSet<>()).add(argument.getName()));
                        continue;
                    }
                    // "id", "<Book>.isbn | <Electronics>.sku", "product.id": the first field of each alternative
                    for (String alternative : mapped.split("\\|")) {
                        Matcher typed = Pattern.compile("^\\s*<(\\w+)>\\s*\\.\\s*(\\w+)").matcher(alternative);
                        Matcher plain = Pattern.compile("^\\s*\\{?\\s*(\\w+)").matcher(alternative);
                        if (typed.find()) {
                            keyFields.computeIfAbsent(typed.group(1), k -> new HashSet<>()).add(typed.group(2));
                        } else if (plain.find()) {
                            targets.forEach(t -> keyFields.computeIfAbsent(t, k -> new HashSet<>()).add(plain.group(1)));
                        }
                    }
                }
            }
        }
    }

    private static List<String> possibleTypes(GraphQLSchema schema, GraphQLType type) {
        List<String> names = new ArrayList<>();
        if (type instanceof GraphQLInterfaceType interfaceType) {
            schema.getImplementations(interfaceType).forEach(t -> names.add(t.getName()));
        } else if (type instanceof GraphQLUnionType unionType) {
            unionType.getTypes().forEach(t -> names.add(t.getName()));
        }
        names.sort(null);
        return names;
    }

    private static String stringArgument(GraphQLAppliedDirective directive, String name) {
        GraphQLAppliedDirectiveArgument argument = directive.getArgument(name);
        if (argument == null) {
            return null;
        }
        Object value = argument.getValue();
        if (value instanceof StringValue stringValue) {
            return stringValue.getValue();
        }
        return value instanceof String s ? s : null;
    }

    private static List<String> topLevelNames(String selection) {
        List<String> names = new ArrayList<>();
        int depth = 0;
        for (String token : selection.replace("{", " { ").replace("}", " } ").trim().split("[\\s,]+")) {
            if (token.equals("{")) {
                depth++;
            } else if (token.equals("}")) {
                depth--;
            } else if (depth == 0 && !token.isEmpty()) {
                names.add(token);
            }
        }
        return names;
    }

    static List<Long> seedsIn(String text) {
        List<Long> seeds = new ArrayList<>();
        Matcher m = SEED.matcher(text);
        while (m.find()) {
            seeds.add(Long.parseLong(m.group(1) != null ? m.group(1) : m.group(2)));
        }
        return seeds;
    }

    static String canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            new TreeMap<>(map).forEach((k, v) -> sb.append(k).append(':').append(canonical(v)).append(','));
            return sb.append('}').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            list.forEach(v -> sb.append(canonical(v)).append(','));
            return sb.append(']').toString();
        }
        return value instanceof String s ? "\"" + s + "\"" : String.valueOf(value);
    }

    static long hash(String text) {
        long h = 0x811c9dc5L;
        for (int i = 0; i < text.length(); i++) {
            h ^= text.charAt(i);
            h = (h * 0x01000193L) & 0xffffffffL;
        }
        return (h % 900000000L) + 100000L;
    }

}
