package dev.feddi.federation.engine.benchmark;

import graphql.GraphQL;
import graphql.schema.Coercing;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.language.IntValue;
import graphql.language.StringValue;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Executable versions of the graphql-gateway-benchmarks composite-schema subgraphs
 * (accounts, products, inventory, reviews) with the benchmark's data, plus a single
 * "monolith" schema serving the same data. The monolith is the oracle: whatever the
 * gateway returns for a query must equal what the monolith returns.
 *
 * <p>Data and behavior mirror the .NET subgraphs in
 * ChilliCream/graphql-gateway-benchmarks (composite-schema/subgraphs-net).
 */
public final class BenchmarkSubgraphs {

    public static final String ACCOUNTS = "accounts";
    public static final String PRODUCTS = "products";
    public static final String INVENTORY = "inventory";
    public static final String REVIEWS = "reviews";

    public static final List<String> NAMES = List.of(ACCOUNTS, PRODUCTS, INVENTORY, REVIEWS);

    private static final List<Map<String, Object>> USERS = List.of(
        user("1", "Uri Goldshtein", "urigo"),
        user("2", "Dotan Simha", "dotansimha"),
        user("3", "Kamil Kisiela", "kamilkisiela"),
        user("4", "Arda Tanrikulu", "ardatan"),
        user("5", "Gil Gardosh", "gilgardosh"),
        user("6", "Laurin Quast", "laurin"));

    private static final List<Map<String, Object>> PRODUCTS_DATA = List.of(
        product("1", "Table", 899, 100, true),
        product("2", "Couch", 1299, 1000, false),
        product("3", "Glass", 15, 20, false),
        product("4", "Chair", 499, 100, false),
        product("5", "TV", 1299, 1000, true),
        product("6", "Lamp", 6999, 300, true),
        product("7", "Grill", 3999, 2000, true),
        product("8", "Fridge", 100000, 6000, false),
        product("9", "Sofa", 9999, 800, true));

    private static final List<Map<String, Object>> REVIEWS_DATA = List.of(
        review("1", "1"), review("2", "1"), review("3", "1"), review("4", "1"),
        review("5", "2"), review("6", "2"), review("7", "2"), review("8", "2"),
        review("9", "3"), review("10", "4"), review("11", "4"));

    private static final String ACCOUNTS_SDL = """
        type Query {
          me: User
          user(id: ID!): User
          users: [User!]!
        }
        type User {
          id: ID!
          name: String
          username: String
          birthday: Int
        }
        """;

    private static final String PRODUCTS_SDL = """
        type Query {
          topProducts(first: Int! = 5): [Product!]!
          product(upc: ID!): Product
        }
        type Product {
          upc: String!
          name: String!
          price: Long!
          weight: Long!
        }
        scalar Long
        """;

    private static final String INVENTORY_SDL = """
        type Query {
          productByUpc(upc: ID!): Product
        }
        type Product {
          shippingEstimate(weight: Long!, price: Long!): Long
          upc: String!
          inStock: Boolean!
        }
        scalar Long
        """;

    private static final String REVIEWS_SDL = """
        type Query {
          product(upc: ID!): Product
          review(id: ID!): Review
          user(id: ID!): User
        }
        type Product {
          reviews: [Review!]!
          upc: String!
        }
        type Review {
          id: ID!
          author: User
          product: Product
          body: String!
          authorId: String!
          productUpc: String!
        }
        type User {
          id: ID!
          reviews: [Review!]!
        }
        """;

    private static final String MONOLITH_SDL = """
        type Query {
          me: User
          users: [User!]!
          topProducts(first: Int! = 5): [Product!]!
        }
        type User {
          id: ID!
          name: String
          username: String
          birthday: Int
          reviews: [Review!]!
        }
        type Product {
          upc: String!
          name: String!
          price: Long!
          weight: Long!
          inStock: Boolean!
          shippingEstimate: Long
          reviews: [Review!]!
        }
        type Review {
          id: ID!
          body: String!
          author: User
          product: Product
        }
        scalar Long
        """;

    private BenchmarkSubgraphs() {
    }

    /**
     * Builds the executable schema for one subgraph.
     */
    public static GraphQLSchema subgraph(String name) {
        return switch (name) {
            case ACCOUNTS -> build(ACCOUNTS_SDL, w -> w
                .type("Query", t -> t
                    .dataFetcher("me", env -> USERS.get(0))
                    .dataFetcher("users", env -> USERS)
                    .dataFetcher("user", env -> find(USERS, "id", env.getArgument("id")))));
            case PRODUCTS -> build(PRODUCTS_SDL, w -> w
                .type("Query", t -> t
                    .dataFetcher("topProducts", env -> topProducts(env.getArgument("first")))
                    .dataFetcher("product", env -> find(PRODUCTS_DATA, "upc", env.getArgument("upc")))));
            case INVENTORY -> build(INVENTORY_SDL, w -> w
                .type("Query", t -> t
                    .dataFetcher("productByUpc", env -> find(PRODUCTS_DATA, "upc", env.getArgument("upc"))))
                .type("Product", t -> t
                    .dataFetcher("shippingEstimate", env -> shippingEstimate(
                        toLong(env.getArgument("price")), toLong(env.getArgument("weight"))))));
            case REVIEWS -> build(REVIEWS_SDL, w -> w
                .type("Query", t -> t
                    .dataFetcher("product", env -> ref("upc", find(PRODUCTS_DATA, "upc", env.getArgument("upc"))))
                    .dataFetcher("review", env -> find(REVIEWS_DATA, "id", env.getArgument("id")))
                    .dataFetcher("user", env -> ref("id", find(USERS, "id", env.getArgument("id")))))
                .type("Product", t -> t
                    .dataFetcher("reviews", env -> reviewsByProduct(upcOf(env.getSource()))))
                .type("User", t -> t
                    .dataFetcher("reviews", env -> reviewsByUser()))
                .type("Review", t -> t
                    .dataFetcher("author", env -> ref("id", find(USERS, "id", sourceValue(env.getSource(), "authorId"))))
                    .dataFetcher("product", env ->
                        ref("upc", find(PRODUCTS_DATA, "upc", sourceValue(env.getSource(), "productUpc"))))));
            default -> throw new IllegalArgumentException("Unknown benchmark subgraph: " + name);
        };
    }

    /**
     * Builds the single-schema oracle serving the same data as all subgraphs together.
     */
    public static GraphQL monolith() {
        GraphQLSchema schema = build(MONOLITH_SDL, w -> w
            .type("Query", t -> t
                .dataFetcher("me", env -> USERS.get(0))
                .dataFetcher("users", env -> USERS)
                .dataFetcher("topProducts", env -> topProducts(env.getArgument("first"))))
            .type("User", t -> t
                .dataFetcher("reviews", env -> reviewsByUser()))
            .type("Product", t -> t
                .dataFetcher("reviews", env -> reviewsByProduct(upcOf(env.getSource())))
                .dataFetcher("shippingEstimate", env -> {
                    Map<String, Object> p = env.getSource();
                    return shippingEstimate((Long) p.get("price"), (Long) p.get("weight"));
                }))
            .type("Review", t -> t
                .dataFetcher("author", env -> find(USERS, "id", sourceValue(env.getSource(), "authorId")))
                .dataFetcher("product", env -> find(PRODUCTS_DATA, "upc", sourceValue(env.getSource(), "productUpc")))));
        return GraphQL.newGraphQL(schema).build();
    }

    private static GraphQLSchema build(String sdl, UnaryOperator<RuntimeWiring.Builder> wiring) {
        RuntimeWiring.Builder builder = RuntimeWiring.newRuntimeWiring().scalar(LONG);
        return new SchemaGenerator().makeExecutableSchema(new SchemaParser().parse(sdl), wiring.apply(builder).build());
    }

    private static List<Map<String, Object>> topProducts(Integer first) {
        int n = first == null ? 5 : first;
        return PRODUCTS_DATA.subList(0, Math.min(n, PRODUCTS_DATA.size()));
    }

    // Mirrors the .NET subgraph: User.reviews always returns reviews 1 and 2, ignoring the user.
    private static List<Map<String, Object>> reviewsByUser() {
        return REVIEWS_DATA.subList(0, 2);
    }

    private static List<Map<String, Object>> reviewsByProduct(String upc) {
        return REVIEWS_DATA.stream().filter(r -> r.get("productUpc").equals(upc)).toList();
    }

    private static Long shippingEstimate(long price, long weight) {
        return price > 1000 ? 0L : weight / 2;
    }

    private static String upcOf(Object source) {
        return sourceValue(source, "upc");
    }

    @SuppressWarnings("unchecked")
    private static String sourceValue(Object source, String field) {
        return (String) ((Map<String, Object>) source).get(field);
    }

    private static Map<String, Object> find(List<Map<String, Object>> rows, String field, Object value) {
        if (value == null) {
            return null;
        }
        return rows.stream().filter(r -> r.get(field).equals(value.toString())).findFirst().orElse(null);
    }

    // Reviews only knows the key of users and products, like the real subgraph.
    private static Map<String, Object> ref(String keyField, Map<String, Object> row) {
        return row == null ? null : Map.of(keyField, row.get(keyField));
    }

    private static long toLong(Object value) {
        return value instanceof Number n ? n.longValue() : Long.parseLong(value.toString());
    }

    private static Map<String, Object> user(String id, String name, String username) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", id);
        user.put("name", name);
        user.put("username", username);
        user.put("birthday", 1234567890);
        return user;
    }

    private static Map<String, Object> product(String upc, String name, long price, long weight, boolean inStock) {
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("upc", upc);
        product.put("name", name);
        product.put("price", price);
        product.put("weight", weight);
        product.put("inStock", inStock);
        return product;
    }

    private static Map<String, Object> review(String id, String productUpc) {
        Map<String, Object> review = new LinkedHashMap<>();
        review.put("id", id);
        review.put("body", "Review body " + id);
        review.put("authorId", "1");
        review.put("productUpc", productUpc);
        return review;
    }

    private static final GraphQLScalarType LONG = GraphQLScalarType.newScalar()
        .name("Long")
        .coercing(new Coercing<Long, Long>() {
            @Override
            @SuppressWarnings("deprecation")
            public Long serialize(Object value) {
                return toLong(value);
            }

            @Override
            @SuppressWarnings("deprecation")
            public Long parseValue(Object input) {
                return toLong(input);
            }

            @Override
            @SuppressWarnings("deprecation")
            public Long parseLiteral(Object input) {
                if (input instanceof IntValue iv) {
                    BigInteger v = iv.getValue();
                    return v.longValue();
                }
                if (input instanceof StringValue sv) {
                    return Long.parseLong(sv.getValue());
                }
                throw new IllegalArgumentException("Not a Long literal: " + input);
            }
        })
        .build();

    /**
     * Convenience for tests that need fetchers keyed by subgraph.
     */
    public static Map<String, GraphQLSchema> allSubgraphs() {
        Map<String, GraphQLSchema> all = new LinkedHashMap<>();
        for (String name : NAMES) {
            all.put(name, subgraph(name));
        }
        return all;
    }
}
