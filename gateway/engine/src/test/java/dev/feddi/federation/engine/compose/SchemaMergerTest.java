package dev.feddi.federation.engine.compose;

import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLUnionType;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for SchemaMerger that verify correct merging of GraphQL schemas.
 */
class SchemaMergerTest {

    private SchemaMerger merger;
    private SchemaParser schemaParser;

    @BeforeEach
    void setUp() {
        merger = new SchemaMerger();
        schemaParser = new SchemaParser();
    }

    private GraphQLSchema parseSchema(String sdl) {
        TypeDefinitionRegistry registry = schemaParser.parse(sdl);
        RuntimeWiring wiring = RuntimeWiring.newRuntimeWiring()
            .wiringFactory(new NoOpWiringFactory())
            .build();
        return new SchemaGenerator().makeExecutableSchema(registry, wiring);
    }

    @Nested
    @DisplayName("Basic Type Merging")
    class BasicTypeMerging {

        @Test
        @DisplayName("should add types from second schema that don't exist in first")
        void addNewTypes() {
            GraphQLSchema first = parseSchema("""
                type Query {
                    users: [User]
                }
                type User {
                    id: ID!
                    name: String
                }
                """);

            GraphQLSchema second = parseSchema("""
                type Query {
                    products: [Product]
                }
                type Product {
                    id: ID!
                    title: String
                }
                """);

            GraphQLSchema merged = merger.merge(first, second);

            // Both types should exist
            assertThat(merged.getType("User")).isNotNull();
            assertThat(merged.getType("Product")).isNotNull();

            // Both query fields should exist
            GraphQLObjectType query = merged.getQueryType();
            assertThat(query.getFieldDefinition("users")).isNotNull();
            assertThat(query.getFieldDefinition("products")).isNotNull();
        }

        @Test
        @DisplayName("should merge object types with same name by combining fields")
        void mergeObjectTypes() {
            GraphQLSchema first = parseSchema("""
                type Query {
                    user(id: ID!): User
                }
                type User {
                    id: ID!
                    name: String
                }
                """);

            GraphQLSchema second = parseSchema("""
                type Query {
                    userByEmail(email: String!): User
                }
                type User {
                    id: ID!
                    email: String
                    orders: [String]
                }
                """);

            GraphQLSchema merged = merger.merge(first, second);

            GraphQLObjectType userType = (GraphQLObjectType) merged.getType("User");
            assertThat(userType).isNotNull();
            
            // All fields from both schemas should be present
            assertThat(userType.getFieldDefinition("id")).isNotNull();
            assertThat(userType.getFieldDefinition("name")).isNotNull();
            assertThat(userType.getFieldDefinition("email")).isNotNull();
            assertThat(userType.getFieldDefinition("orders")).isNotNull();
        }
    }

    @Nested
    @DisplayName("Enum Type Merging")
    class EnumTypeMerging {

        @Test
        @DisplayName("should merge enum types by combining values")
        void mergeEnumTypes() {
            GraphQLSchema first = parseSchema("""
                type Query {
                    status: Status
                }
                enum Status {
                    ACTIVE
                    INACTIVE
                }
                """);

            GraphQLSchema second = parseSchema("""
                type Query {
                    orderStatus: Status
                }
                enum Status {
                    ACTIVE
                    PENDING
                    CANCELLED
                }
                """);

            GraphQLSchema merged = merger.merge(first, second);

            GraphQLEnumType statusType = (GraphQLEnumType) merged.getType("Status");
            assertThat(statusType).isNotNull();
            
            // All enum values should be present
            assertThat(statusType.getValue("ACTIVE")).isNotNull();
            assertThat(statusType.getValue("INACTIVE")).isNotNull();
            assertThat(statusType.getValue("PENDING")).isNotNull();
            assertThat(statusType.getValue("CANCELLED")).isNotNull();
        }
    }

    @Nested
    @DisplayName("Input Type Merging")
    class InputTypeMerging {

        @Test
        @DisplayName("should merge input types by combining fields")
        void mergeInputTypes() {
            GraphQLSchema first = parseSchema("""
                type Query {
                    search(filter: SearchInput): [String]
                }
                input SearchInput {
                    query: String
                    limit: Int
                }
                """);

            GraphQLSchema second = parseSchema("""
                type Query {
                    advancedSearch(filter: SearchInput): [String]
                }
                input SearchInput {
                    query: String
                    offset: Int
                    category: String
                }
                """);

            GraphQLSchema merged = merger.merge(first, second);

            GraphQLInputObjectType inputType = (GraphQLInputObjectType) merged.getType("SearchInput");
            assertThat(inputType).isNotNull();
            
            // All input fields should be present
            assertThat(inputType.getFieldDefinition("query")).isNotNull();
            assertThat(inputType.getFieldDefinition("limit")).isNotNull();
            assertThat(inputType.getFieldDefinition("offset")).isNotNull();
            assertThat(inputType.getFieldDefinition("category")).isNotNull();
        }
    }

    @Nested
    @DisplayName("Interface Merging")
    class InterfaceMerging {

        @Test
        @DisplayName("should merge interface types by combining fields")
        void mergeInterfaceTypes() {
            // First schema: Node has id, User implements Node
            GraphQLSchema first = parseSchema("""
                type Query {
                    nodes: [Node]
                }
                interface Node {
                    id: ID!
                }
                type User implements Node {
                    id: ID!
                    name: String
                }
                """);

            // Second schema: Same interface (id only), Product implements Node
            GraphQLSchema second = parseSchema("""
                type Query {
                    products: [Node]
                }
                interface Node {
                    id: ID!
                }
                type Product implements Node {
                    id: ID!
                    title: String
                }
                """);

            GraphQLSchema merged = merger.merge(first, second);

            // Verify interface exists
            GraphQLInterfaceType nodeType = (GraphQLInterfaceType) merged.getType("Node");
            assertThat(nodeType).isNotNull();
            assertThat(nodeType.getFieldDefinition("id")).isNotNull();

            // Verify both implementing types exist
            assertThat(merged.getType("User")).isNotNull();
            assertThat(merged.getType("Product")).isNotNull();
        }

        @Test
        @DisplayName("should merge interfaces with same fields from different schemas")
        void mergeInterfacesWithSameFields() {
            // Both schemas define the same interface with same fields
            GraphQLSchema first = parseSchema("""
                type Query {
                    users: [User]
                }
                interface Entity {
                    id: ID!
                    createdAt: String
                }
                type User implements Entity {
                    id: ID!
                    createdAt: String
                    name: String
                }
                """);

            GraphQLSchema second = parseSchema("""
                type Query {
                    products: [Product]
                }
                interface Entity {
                    id: ID!
                    createdAt: String
                }
                type Product implements Entity {
                    id: ID!
                    createdAt: String
                    title: String
                }
                """);

            GraphQLSchema merged = merger.merge(first, second);

            GraphQLInterfaceType entityType = (GraphQLInterfaceType) merged.getType("Entity");
            assertThat(entityType).isNotNull();
            assertThat(entityType.getFieldDefinition("id")).isNotNull();
            assertThat(entityType.getFieldDefinition("createdAt")).isNotNull();

            // Both implementing types should be merged
            assertThat(merged.getType("User")).isNotNull();
            assertThat(merged.getType("Product")).isNotNull();
        }
    }

    @Nested
    @DisplayName("Union Merging")
    class UnionMerging {

        @Test
        @DisplayName("should merge union types by combining member types")
        void mergeUnionTypes() {
            GraphQLSchema first = parseSchema("""
                type Query {
                    search: [SearchResult]
                }
                union SearchResult = User
                type User {
                    id: ID!
                    name: String
                }
                """);

            GraphQLSchema second = parseSchema("""
                type Query {
                    products: [Product]
                }
                union SearchResult = Product
                type Product {
                    id: ID!
                    title: String
                }
                """);

            GraphQLSchema merged = merger.merge(first, second);

            GraphQLUnionType unionType = (GraphQLUnionType) merged.getType("SearchResult");
            assertThat(unionType).isNotNull();
            
            // Union should contain both possible types
            List<String> typeNames = unionType.getTypes().stream()
                .map(GraphQLNamedType::getName)
                .toList();
            assertThat(typeNames).containsExactlyInAnyOrder("User", "Product");
        }
    }

    @Nested
    @DisplayName("MergeAll")
    class MergeAll {

        @Test
        @DisplayName("should merge multiple schemas")
        void mergeMultipleSchemas() {
            GraphQLSchema schema1 = parseSchema("""
                type Query {
                    users: [User]
                }
                type User {
                    id: ID!
                }
                """);

            GraphQLSchema schema2 = parseSchema("""
                type Query {
                    products: [Product]
                }
                type Product {
                    id: ID!
                }
                """);

            GraphQLSchema schema3 = parseSchema("""
                type Query {
                    orders: [Order]
                }
                type Order {
                    id: ID!
                }
                """);

            GraphQLSchema merged = merger.mergeAll(List.of(schema1, schema2, schema3));

            // All types should exist
            assertThat(merged.getType("User")).isNotNull();
            assertThat(merged.getType("Product")).isNotNull();
            assertThat(merged.getType("Order")).isNotNull();

            // All query fields should exist
            GraphQLObjectType query = merged.getQueryType();
            assertThat(query.getFieldDefinition("users")).isNotNull();
            assertThat(query.getFieldDefinition("products")).isNotNull();
            assertThat(query.getFieldDefinition("orders")).isNotNull();
        }

        @Test
        @DisplayName("should throw when merging empty list")
        void throwOnEmptyList() {
            assertThatThrownBy(() -> merger.mergeAll(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At least one schema is required");
        }

        @Test
        @DisplayName("should return equivalent schema when merging single schema")
        void returnEquivalentForSingleSchema() {
            GraphQLSchema schema = parseSchema("""
                type Query {
                    hello: String
                }
                """);

            GraphQLSchema merged = merger.mergeAll(List.of(schema));

            // The merged schema should have the same structure
            assertThat(merged.getQueryType()).isNotNull();
            assertThat(merged.getQueryType().getFieldDefinition("hello")).isNotNull();
        }
    }

    @Nested
    @DisplayName("@require Argument Removal")
    class RequireArgumentRemoval {

        private dev.feddi.federation.engine.compose.SubgraphParser subgraphParser;

        @BeforeEach
        void setUpRequireTests() {
            subgraphParser = new dev.feddi.federation.engine.compose.SubgraphParser();
        }

        @Test
        @DisplayName("should remove arguments with @require from supergraph schema")
        void removeRequireArguments() {
            // Source schema with @require arguments - all args have @require
            var subgraph = subgraphParser.parse("a", """
                type Query {
                    productByUpc(upc: String!): Product @lookup
                }

                type Product @key(fields: "upc") {
                    upc: String!
                    price: Int @shareable
                    weight: Int @shareable
                    shippingEstimate(price: Int @require(field: "price"), weight: Int @require(field: "weight")): Int
                }
                """);

            GraphQLSchema merged = merger.mergeAll(List.of(subgraph.schema()));

            // The shippingEstimate field should have NO arguments
            // @require arguments must be removed from the supergraph
            GraphQLObjectType productType = (GraphQLObjectType) merged.getType("Product");
            assertThat(productType).isNotNull();

            var shippingEstimateField = productType.getFieldDefinition("shippingEstimate");
            assertThat(shippingEstimateField).isNotNull();
            assertThat(shippingEstimateField.getArguments())
                .as("@require arguments should be removed from supergraph schema")
                .isEmpty();
        }

        @Test
        @DisplayName("should keep non-@require arguments and remove only @require arguments")
        void keepNonRequireArguments() {
            // Source schema with mixed arguments: one regular, one with @require
            var subgraph = subgraphParser.parse("a", """
                type Query {
                    productByUpc(upc: String!): Product @lookup
                }

                type Product @key(fields: "upc") {
                    upc: String!
                    dimension: Dimension @shareable
                    delivery(zip: String!, size: Int @require(field: "dimension.size")): DeliveryEstimate
                }

                type Dimension @shareable {
                    size: Int
                }

                type DeliveryEstimate {
                    fastestDelivery: String
                    price: Int
                }
                """);

            GraphQLSchema merged = merger.mergeAll(List.of(subgraph.schema()));

            GraphQLObjectType productType = (GraphQLObjectType) merged.getType("Product");
            assertThat(productType).isNotNull();

            var deliveryField = productType.getFieldDefinition("delivery");
            assertThat(deliveryField).isNotNull();

            // Should have only 'zip' argument, 'size' with @require should be removed
            assertThat(deliveryField.getArguments())
                .as("Only non-@require arguments should remain")
                .hasSize(1);
            assertThat(deliveryField.getArgument("zip"))
                .as("zip argument should be kept")
                .isNotNull();
            assertThat(deliveryField.getArgument("size"))
                .as("size argument with @require should be removed")
                .isNull();
        }

        @Test
        @DisplayName("should remove @require arguments in merged types from multiple schemas")
        void removeRequireArgumentsInMergedTypes() {
            // First schema has Product with shippingEstimate(@require)
            var subgraphA = subgraphParser.parse("a", """
                type Query {
                    productByUpc(upc: String!): Product @lookup
                }

                type Product @key(fields: "upc") {
                    upc: String!
                    price: Int @shareable
                    shippingEstimate(price: Int @require(field: "price")): Int
                }
                """);

            // Second schema has Product with additional fields
            var subgraphB = subgraphParser.parse("b", """
                type Query {
                    products: [Product]
                }

                type Product @key(fields: "upc") {
                    upc: String!
                    name: String
                    price: Int @shareable
                }
                """);

            GraphQLSchema merged = merger.merge(subgraphA.schema(), subgraphB.schema());

            GraphQLObjectType productType = (GraphQLObjectType) merged.getType("Product");
            assertThat(productType).isNotNull();

            // shippingEstimate should exist but with no arguments
            var shippingEstimateField = productType.getFieldDefinition("shippingEstimate");
            assertThat(shippingEstimateField).isNotNull();
            assertThat(shippingEstimateField.getArguments())
                .as("@require arguments should be removed even in merged types")
                .isEmpty();

            // Other fields should be present
            assertThat(productType.getFieldDefinition("name")).isNotNull();
            assertThat(productType.getFieldDefinition("price")).isNotNull();
        }
    }

    @Nested
    @DisplayName("Error Cases")
    class ErrorCases {

        @Test
        @DisplayName("should throw when merging types with different kinds")
        void throwOnDifferentKinds() {
            GraphQLSchema first = parseSchema("""
                type Query {
                    status: Status
                }
                type Status {
                    code: Int
                }
                """);

            GraphQLSchema second = parseSchema("""
                type Query {
                    orderStatus: Status
                }
                enum Status {
                    ACTIVE
                    INACTIVE
                }
                """);

            assertThatThrownBy(() -> merger.merge(first, second))
                .isInstanceOf(SchemaMerger.SchemaMergeException.class)
                .hasMessageContaining("different kinds");
        }
    }

    /**
     * Simple WiringFactory that provides no-op implementations for testing.
     */
    private static class NoOpWiringFactory implements graphql.schema.idl.WiringFactory {
        @Override
        public boolean providesTypeResolver(graphql.schema.idl.InterfaceWiringEnvironment env) {
            return true;
        }

        @Override
        public graphql.schema.TypeResolver getTypeResolver(graphql.schema.idl.InterfaceWiringEnvironment env) {
            return e -> null;
        }

        @Override
        public boolean providesTypeResolver(graphql.schema.idl.UnionWiringEnvironment env) {
            return true;
        }

        @Override
        public graphql.schema.TypeResolver getTypeResolver(graphql.schema.idl.UnionWiringEnvironment env) {
            return e -> null;
        }

        @Override
        public boolean providesDataFetcher(graphql.schema.idl.FieldWiringEnvironment env) {
            return true;
        }

        @Override
        public graphql.schema.DataFetcher<?> getDataFetcher(graphql.schema.idl.FieldWiringEnvironment env) {
            return e -> null;
        }
    }
}
