package dev.feddi.federation.engine.compose.validation.rules;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.compose.SubgraphParser;
import dev.feddi.federation.engine.compose.validation.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for UnreachableTypeRule.
 *
 * Validates that all Interface, Object, and Union types in a subgraph
 * are reachable from root types (Query/Mutation/Subscription).
 */
class UnreachableTypeRuleTest {

    private static final String CODE = "UNREACHABLE_TYPE";

    private SubgraphParser parser;
    private UnreachableTypeRule rule;

    @BeforeEach
    void setUp() {
        parser = new SubgraphParser();
        rule = new UnreachableTypeRule();
    }

    private Subgraph subgraph(String name, String sdl) {
        return parser.parse(name, "http://" + name, sdl);
    }

    private ValidationResult validate(Subgraph... subgraphs) {
        return rule.validate(List.of(subgraphs));
    }

    private void assertValid(ValidationResult result) {
        assertThat(result.isValid())
            .as("Expected validation to pass but got errors: %s", result.errors())
            .isTrue();
    }

    private void assertInvalid(ValidationResult result) {
        assertThat(result.hasErrors())
            .as("Expected validation to fail")
            .isTrue();
        assertThat(result.errors())
            .anyMatch(d -> d.code().equals(CODE));
    }

    private void assertInvalidWithCoordinate(ValidationResult result, String coordinate) {
        assertInvalid(result);
        assertThat(result.errors())
            .anyMatch(d -> d.code().equals(CODE) && d.coordinate().equals(coordinate));
    }

    private void assertValidType(ValidationResult result, String typeName) {
        assertThat(result.errors())
            .noneMatch(d -> d.code().equals(CODE) && d.coordinate().equals(typeName));
    }

    // ========================================================================
    // Valid Cases - All types are reachable
    // ========================================================================

    @Nested
    class ValidCases {

        @Test
        void simpleQueryWithObjectType() {
            Subgraph products = subgraph("products", """
                type Query {
                    product: Product
                }
                type Product {
                    id: ID!
                    name: String
                }
                """);

            ValidationResult result = validate(products);
            assertValid(result);
        }

        @Test
        void nestedObjectTypes() {
            Subgraph products = subgraph("products", """
                type Query {
                    product: Product
                }
                type Product {
                    id: ID!
                    category: Category
                }
                type Category {
                    id: ID!
                    name: String
                }
                """);

            ValidationResult result = validate(products);
            assertValid(result);
        }

        @Test
        void deeplyNestedTypes() {
            Subgraph products = subgraph("products", """
                type Query {
                    order: Order
                }
                type Order {
                    items: [OrderItem]
                }
                type OrderItem {
                    product: Product
                    quantity: Int
                }
                type Product {
                    id: ID!
                    name: String
                }
                """);

            ValidationResult result = validate(products);
            assertValid(result);
        }

        @Test
        void interfaceWithImplementations() {
            Subgraph products = subgraph("products", """
                type Query {
                    node: Node
                }
                interface Node {
                    id: ID!
                }
                type Product implements Node {
                    id: ID!
                    name: String
                }
                type Category implements Node {
                    id: ID!
                    title: String
                }
                """);

            ValidationResult result = validate(products);
            assertValid(result);
        }

        @Test
        void unionType() {
            Subgraph search = subgraph("search", """
                type Query {
                    search: [SearchResult]
                }
                union SearchResult = Product | Category
                type Product {
                    id: ID!
                    name: String
                }
                type Category {
                    id: ID!
                    title: String
                }
                """);

            ValidationResult result = validate(search);
            assertValid(result);
        }

        @Test
        void mutationRootType() {
            Subgraph products = subgraph("products", """
                type Query {
                    products: [Product]
                }
                type Mutation {
                    createProduct(input: ProductInput!): Product
                }
                type Product {
                    id: ID!
                    name: String
                }
                input ProductInput {
                    name: String!
                }
                """);

            ValidationResult result = validate(products);
            assertValid(result);
        }

        @Test
        void subscriptionRootType() {
            Subgraph products = subgraph("products", """
                type Query {
                    products: [Product]
                }
                type Subscription {
                    productCreated: Product
                }
                type Product {
                    id: ID!
                    name: String
                }
                """);

            ValidationResult result = validate(products);
            assertValid(result);
        }

        @Test
        void typeReachableViaArgument() {
            // ProductFilter is reachable as an argument type
            Subgraph products = subgraph("products", """
                type Query {
                    products(filter: ProductFilter): [Product]
                }
                type Product {
                    id: ID!
                    name: String
                }
                input ProductFilter {
                    category: CategoryFilter
                }
                input CategoryFilter {
                    name: String
                }
                """);

            ValidationResult result = validate(products);
            assertValid(result);
        }

        @Test
        void circularReference() {
            Subgraph products = subgraph("products", """
                type Query {
                    product: Product
                }
                type Product {
                    id: ID!
                    relatedProducts: [Product]
                    category: Category
                }
                type Category {
                    id: ID!
                    products: [Product]
                }
                """);

            ValidationResult result = validate(products);
            assertValid(result);
        }

        @Test
        void typeReachableOnlyViaMutation() {
            // CreateProductResult is only reachable via Mutation
            Subgraph products = subgraph("products", """
                type Query {
                    products: [Product]
                }
                type Mutation {
                    createProduct: CreateProductResult
                }
                type Product {
                    id: ID!
                }
                type CreateProductResult {
                    success: Boolean
                    product: Product
                }
                """);

            ValidationResult result = validate(products);
            assertValid(result);
        }

        @Test
        void interfaceFieldReturnsType() {
            // ReviewableItem interface has a field returning Review
            // Review should be reachable
            Subgraph reviews = subgraph("reviews", """
                type Query {
                    reviewable: ReviewableItem
                }
                interface ReviewableItem {
                    id: ID!
                    reviews: [Review]
                }
                type Product implements ReviewableItem {
                    id: ID!
                    reviews: [Review]
                }
                type Review {
                    id: ID!
                    rating: Int
                }
                """);

            ValidationResult result = validate(reviews);
            assertValid(result);
        }

        @Test
        void multipleSubgraphsAllValid() {
            Subgraph products = subgraph("products", """
                type Query {
                    product: Product
                }
                type Product {
                    id: ID!
                }
                """);

            Subgraph reviews = subgraph("reviews", """
                type Query {
                    review: Review
                }
                type Review {
                    id: ID!
                }
                """);

            ValidationResult result = validate(products, reviews);
            assertValid(result);
        }
    }

    // ========================================================================
    // Invalid Cases - Unreachable types
    // ========================================================================

    @Nested
    class InvalidCases {

        @Test
        void simpleUnreachableType() {
            Subgraph products = subgraph("products", """
                type Query {
                    hello: String
                }
                type Product {
                    id: ID!
                    name: String
                }
                """);

            ValidationResult result = validate(products);
            assertInvalidWithCoordinate(result, "Product");
        }

        @Test
        void multipleUnreachableTypes() {
            Subgraph products = subgraph("products", """
                type Query {
                    hello: String
                }
                type Product {
                    id: ID!
                }
                type Category {
                    id: ID!
                }
                type Order {
                    id: ID!
                }
                """);

            ValidationResult result = validate(products);
            assertThat(result.errors())
                .hasSize(3)
                .anyMatch(d -> d.code().equals(CODE) && d.coordinate().equals("Product"))
                .anyMatch(d -> d.code().equals(CODE) && d.coordinate().equals("Category"))
                .anyMatch(d -> d.code().equals(CODE) && d.coordinate().equals("Order"));
        }

        @Test
        void unreachableInterface() {
            Subgraph products = subgraph("products", """
                type Query {
                    hello: String
                }
                interface Node {
                    id: ID!
                }
                """);

            ValidationResult result = validate(products);
            assertInvalidWithCoordinate(result, "Node");
        }

        @Test
        void unreachableUnion() {
            Subgraph search = subgraph("search", """
                type Query {
                    hello: String
                }
                union SearchResult = Product | Category
                type Product {
                    id: ID!
                }
                type Category {
                    id: ID!
                }
                """);

            ValidationResult result = validate(search);
            // SearchResult, Product, and Category are all unreachable
            assertThat(result.errors())
                .hasSize(3)
                .anyMatch(d -> d.code().equals(CODE) && d.coordinate().equals("SearchResult"))
                .anyMatch(d -> d.code().equals(CODE) && d.coordinate().equals("Product"))
                .anyMatch(d -> d.code().equals(CODE) && d.coordinate().equals("Category"));
        }

        @Test
        void unreachableImplementingType() {
            // Interface is reachable, but one implementing type is not
            // (type doesn't need to implement the interface if it's not returned by the interface)
            Subgraph products = subgraph("products", """
                type Query {
                    node: Node
                }
                interface Node {
                    id: ID!
                }
                type Product implements Node {
                    id: ID!
                }
                type OrphanedType {
                    id: ID!
                    name: String
                }
                """);

            ValidationResult result = validate(products);
            assertInvalidWithCoordinate(result, "OrphanedType");
            // Product should be valid (reachable via Node interface)
            assertValidType(result, "Product");
        }

        @Test
        void partiallyReachableGraph() {
            // Product and Category are reachable, but OrphanedType is not
            Subgraph products = subgraph("products", """
                type Query {
                    product: Product
                }
                type Product {
                    id: ID!
                    category: Category
                }
                type Category {
                    id: ID!
                }
                type OrphanedType {
                    id: ID!
                    name: String
                }
                """);

            ValidationResult result = validate(products);
            assertInvalidWithCoordinate(result, "OrphanedType");
            assertValidType(result, "Product");
            assertValidType(result, "Category");
        }

        @Test
        void disconnectedSubgraph() {
            // Two separate "islands" of types, one connected to Query, one not
            Subgraph products = subgraph("products", """
                type Query {
                    product: Product
                }
                type Product {
                    id: ID!
                    category: Category
                }
                type Category {
                    id: ID!
                }
                type DisconnectedA {
                    id: ID!
                    other: DisconnectedB
                }
                type DisconnectedB {
                    id: ID!
                    back: DisconnectedA
                }
                """);

            ValidationResult result = validate(products);
            assertThat(result.errors())
                .hasSize(2)
                .anyMatch(d -> d.code().equals(CODE) && d.coordinate().equals("DisconnectedA"))
                .anyMatch(d -> d.code().equals(CODE) && d.coordinate().equals("DisconnectedB"));
        }

        @Test
        void unreachableTypeInOneSubgraph() {
            Subgraph valid = subgraph("valid", """
                type Query {
                    product: Product
                }
                type Product {
                    id: ID!
                }
                """);

            Subgraph invalid = subgraph("invalid", """
                type Query {
                    hello: String
                }
                type OrphanedType {
                    id: ID!
                }
                """);

            ValidationResult result = validate(valid, invalid);
            assertThat(result.errors())
                .hasSize(1)
                .anyMatch(d -> d.code().equals(CODE)
                    && d.coordinate().equals("OrphanedType")
                    && d.schemaName().equals("invalid"));
        }

        @Test
        void interfaceOnlyReachableViaUnreachableType() {
            // Node interface is only used by OrphanedType which is unreachable
            Subgraph products = subgraph("products", """
                type Query {
                    hello: String
                }
                interface Node {
                    id: ID!
                }
                type OrphanedType implements Node {
                    id: ID!
                }
                """);

            ValidationResult result = validate(products);
            // Both should be unreachable
            assertThat(result.errors())
                .hasSize(2)
                .anyMatch(d -> d.code().equals(CODE) && d.coordinate().equals("Node"))
                .anyMatch(d -> d.code().equals(CODE) && d.coordinate().equals("OrphanedType"));
        }
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Nested
    class EdgeCases {

        @Test
        void emptyQueryType() {
            // GraphQL requires at least one field on Query, but let's handle gracefully
            // SubgraphParser adds a placeholder field
            Subgraph products = subgraph("products", """
                type Query {
                    _placeholder: String
                }
                type Product {
                    id: ID!
                }
                """);

            ValidationResult result = validate(products);
            assertInvalidWithCoordinate(result, "Product");
        }

        @Test
        void enumsAndScalarsAreNotChecked() {
            // Enums and scalars are allowed to be unreachable
            Subgraph products = subgraph("products", """
                type Query {
                    hello: String
                }
                enum Status {
                    ACTIVE
                    INACTIVE
                }
                scalar DateTime
                """);

            ValidationResult result = validate(products);
            assertValid(result);
        }

        @Test
        void inputTypesAreNotChecked() {
            // Input types are only checked for reachability via arguments
            // An orphaned input type is still valid (might be used by other subgraphs)
            Subgraph products = subgraph("products", """
                type Query {
                    hello: String
                }
                input ProductInput {
                    name: String
                }
                """);

            ValidationResult result = validate(products);
            assertValid(result);
        }

        @Test
        void typeWithKeyButNoLookupIsUnreachable() {
            // @key does NOT make a type automatically reachable.
            // @key is only used to declare key fields as shareable.
            // Types need explicit @lookup fields to be accessible.
            Subgraph products = subgraph("products", """
                type Query {
                    hello: String
                }
                type Product @key(fields: "id") {
                    id: ID!
                    name: String
                }
                """);

            ValidationResult result = validate(products);
            // Even with @key, the type is unreachable without a @lookup
            assertInvalidWithCoordinate(result, "Product");
        }

        @Test
        void lookupMakesTypeReachable() {
            // A @lookup query field makes the type reachable
            Subgraph products = subgraph("products", """
                type Query {
                    productById(id: ID!): Product @lookup
                }
                type Product @key(fields: "id") {
                    id: ID!
                    name: String
                }
                """);

            ValidationResult result = validate(products);
            assertValid(result);
        }

        @Test
        void inaccessibleTypesAreExempt() {
            // Types marked @inaccessible are exempt from reachability check
            // They may be used by resolvers even if not in the schema
            Subgraph products = subgraph("products", """
                type Query {
                    hello: String
                }
                type InternalMetrics @inaccessible {
                    costPrice: Float
                    margin: Float
                }
                interface InternalNode @inaccessible {
                    id: ID!
                }
                union InternalResult @inaccessible = InternalMetrics
                """);

            ValidationResult result = validate(products);
            assertValid(result);
        }

        @Test
        void nestedInterfaceImplementations() {
            // Interfaces implementing other interfaces
            Subgraph products = subgraph("products", """
                type Query {
                    node: Node
                }
                interface Node {
                    id: ID!
                }
                interface Timestamped implements Node {
                    id: ID!
                    createdAt: String
                }
                type Product implements Timestamped & Node {
                    id: ID!
                    createdAt: String
                    name: String
                }
                """);

            ValidationResult result = validate(products);
            assertValid(result);
        }
    }
}
