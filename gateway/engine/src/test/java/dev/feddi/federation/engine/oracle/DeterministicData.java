package dev.feddi.federation.engine.oracle;

import dev.feddi.federation.engine.compose.Subgraph;
import graphql.language.StringValue;
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
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
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
 * {@code 123} for numbers, so a lookup by a key recovers the seed. Every other value derives from
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

    public DeterministicData(List<Subgraph> subgraphs, GraphQLSchema compositeSchema) {
        for (Subgraph subgraph : subgraphs) {
            collect(subgraph.schema());
        }
        for (GraphQLNamedType type : compositeSchema.getAllTypesAsList()) {
            if (type instanceof GraphQLInterfaceType || type instanceof GraphQLUnionType) {
                compositePossibleTypes.put(type.getName(), possibleTypes(compositeSchema, type));
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
        String args = canonical(effectiveArguments(env, parentType, field));
        GraphQLOutputType type = env.getFieldType();
        GraphQLNamedType named = GraphQLTypeUtil.unwrapAllAs(type);
        boolean list = GraphQLTypeUtil.unwrapNonNull(type) instanceof GraphQLList;
        String base = parentType + "." + field + "|" + parentSeed + "|" + args;

        if (named instanceof GraphQLFieldsContainer || named instanceof GraphQLUnionType) {
            List<Long> argSeeds = seedsIn(args);
            if (list) {
                List<Object> items = new ArrayList<>();
                if (!argSeeds.isEmpty()) {
                    argSeeds.forEach(seed -> items.add(entity(env.getGraphQLSchema(), named, seed)));
                } else {
                    for (int i = 0; i < LIST_SIZE; i++) {
                        items.add(entity(env.getGraphQLSchema(), named, hash(base + "|" + i)));
                    }
                }
                return items;
            }
            return entity(env.getGraphQLSchema(), named, argSeeds.isEmpty() ? hash(base) : argSeeds.get(0));
        }
        if (list) {
            List<Object> items = new ArrayList<>();
            for (int i = 0; i < LIST_SIZE; i++) {
                items.add(scalar(named, parentType, field, parentSeed, args, i));
            }
            return items;
        }
        return scalar(named, parentType, field, parentSeed, args, 0);
    }

    private Map<String, Object> entity(GraphQLSchema schema, GraphQLNamedType type, long seed) {
        String concrete = type.getName();
        if (type instanceof GraphQLInterfaceType || type instanceof GraphQLUnionType) {
            // Choose from the composite schema's possible types, so source schemas and the monolith agree.
            List<String> local = possibleTypes(schema, type);
            List<String> candidates = compositePossibleTypes.getOrDefault(type.getName(), local);
            String chosen = candidates.isEmpty() ? null : candidates.get((int) (seed % candidates.size()));
            concrete = local.contains(chosen) ? chosen : local.isEmpty() ? concrete : local.get(0);
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("__type", concrete);
        value.put("__seed", seed);
        return value;
    }

    private Object scalar(GraphQLNamedType type, String parentType, String field, long seed, String args, int index) {
        boolean key = keyFields.getOrDefault(parentType, Set.of()).contains(field);
        long h = key ? seed : hash(parentType + "." + field + "|" + seed + "|" + args + "|" + index);
        if (type instanceof GraphQLEnumType enumType) {
            List<String> values = enumType.getValues().stream().map(GraphQLEnumValueDefinition::getName).sorted().toList();
            return values.get((int) (h % values.size()));
        }
        return switch (type.getName()) {
            case "ID" -> "s" + h;
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
                    String keyField = mapped != null && mapped.matches("\\w+") ? mapped : argument.getName();
                    targets.forEach(t -> keyFields.computeIfAbsent(t, k -> new HashSet<>()).add(keyField));
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
