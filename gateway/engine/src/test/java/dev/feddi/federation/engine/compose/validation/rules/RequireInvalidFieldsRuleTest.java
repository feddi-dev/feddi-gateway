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
 * Comprehensive tests for RequireInvalidFieldsRule covering all FieldSelectionMap syntax.
 *
 * Test categories:
 * - Simple paths: "weight", "dimension.size"
 * - Type conditions: "<Movie>.imdbCode", "media<Movie>.imdbCode"
 * - Object selections: "{ width, height }", "{ w: width, h: height }"
 * - Path-prefixed objects: "dimension.{ size, weight }"
 * - List selections: "items[id]", "items[{ id, name }]"
 * - Nested lists: "matrix[[id]]"
 * - Alternatives: "book.title | movie.name"
 * - Alternatives with type conditions: "<Book>.isbn | <Movie>.imdbCode"
 */
class RequireInvalidFieldsRuleTest {

    private static final String CODE = "REQUIRE_INVALID_FIELDS";

    private SubgraphParser parser;
    private RequireInvalidFieldsRule rule;

    @BeforeEach
    void setUp() {
        parser = new SubgraphParser();
        rule = new RequireInvalidFieldsRule();
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

    private void assertInvalidWithMessage(ValidationResult result, String messageFragment) {
        assertInvalid(result);
        assertThat(result.errors())
            .anyMatch(d -> d.code().equals(CODE) && d.message().contains(messageFragment));
    }

    // ========================================================================
    // Simple Paths
    // ========================================================================

    @Nested
    class SimplePaths {

        @Test
        void valid_simpleField_existsInOtherSchema() {
            Subgraph products = subgraph("products", """
                type Query {
                  products: [Product]
                }
                type Product @key(fields: "id") {
                  id: ID!
                  weight: Float @shareable
                }
                """);

            Subgraph shipping = subgraph("shipping", """
                type Product @key(fields: "id") {
                  id: ID!
                  shippingCost(w: Float @require(field: "weight")): Float
                }
                """);

            assertValid(validate(products, shipping));
        }

        @Test
        void invalid_simpleField_doesNotExist() {
            Subgraph products = subgraph("products", """
                type Query {
                  products: [Product]
                }
                type Product @key(fields: "id") {
                  id: ID!
                }
                """);

            Subgraph shipping = subgraph("shipping", """
                type Product @key(fields: "id") {
                  id: ID!
                  shippingCost(w: Float @require(field: "unknownField")): Float
                }
                """);

            assertInvalidWithMessage(validate(products, shipping), "does not exist");
        }

        @Test
        void invalid_simpleField_onlyInSameSchema() {
            Subgraph shipping = subgraph("shipping", """
                type Query {
                  products: [Product]
                }
                type Product @key(fields: "id") {
                  id: ID!
                  weight: Float
                  shippingCost(w: Float @require(field: "weight")): Float
                }
                """);

            assertInvalidWithMessage(validate(shipping), "only exists in the same schema");
        }

        @Test
        void valid_nestedPath_existsInOtherSchema() {
            Subgraph products = subgraph("products", """
                type Query {
                  products: [Product]
                }
                type Product @key(fields: "id") {
                  id: ID!
                  dimension: Dimension @shareable
                }
                type Dimension @shareable {
                  size: Int @shareable
                  weight: Int @shareable
                }
                """);

            Subgraph shipping = subgraph("shipping", """
                type Product @key(fields: "id") {
                  id: ID!
                  shippingCost(size: Int @require(field: "dimension.size")): Float
                }
                """);

            assertValid(validate(products, shipping));
        }

        @Test
        void invalid_nestedPath_intermediateFieldMissing() {
            Subgraph products = subgraph("products", """
                type Query {
                  products: [Product]
                }
                type Product @key(fields: "id") {
                  id: ID!
                }
                """);

            Subgraph shipping = subgraph("shipping", """
                type Product @key(fields: "id") {
                  id: ID!
                  shippingCost(size: Int @require(field: "dimension.size")): Float
                }
                """);

            assertInvalidWithMessage(validate(products, shipping), "dimension");
        }

        @Test
        void invalid_nestedPath_leafFieldMissing() {
            Subgraph products = subgraph("products", """
                type Query {
                  products: [Product]
                }
                type Product @key(fields: "id") {
                  id: ID!
                  dimension: Dimension @shareable
                }
                type Dimension @shareable {
                  weight: Int @shareable
                }
                """);

            Subgraph shipping = subgraph("shipping", """
                type Product @key(fields: "id") {
                  id: ID!
                  shippingCost(size: Int @require(field: "dimension.size")): Float
                }
                """);

            assertInvalidWithMessage(validate(products, shipping), "size");
        }
    }

    // ========================================================================
    // Type Conditions
    // ========================================================================

    @Nested
    class TypeConditions {

        @Test
        void valid_initialTypeCondition() {
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  media: [Media]
                }
                interface Media {
                  id: ID!
                }
                type Movie implements Media @key(fields: "id") {
                  id: ID!
                  imdbCode: String @shareable
                }
                """);

            Subgraph recommendations = subgraph("recommendations", """
                type Movie @key(fields: "id") {
                  id: ID!
                  similar(code: String @require(field: "<Movie>.imdbCode")): [Movie]
                }
                """);

            assertValid(validate(catalog, recommendations));
        }

        @Test
        void invalid_initialTypeCondition_fieldMissing() {
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  media: [Media]
                }
                interface Media {
                  id: ID!
                }
                type Movie implements Media @key(fields: "id") {
                  id: ID!
                }
                """);

            Subgraph recommendations = subgraph("recommendations", """
                type Movie @key(fields: "id") {
                  id: ID!
                  similar(code: String @require(field: "<Movie>.imdbCode")): [Movie]
                }
                """);

            assertInvalidWithMessage(validate(catalog, recommendations), "imdbCode");
        }

        @Test
        void invalid_initialTypeCondition_typeDoesNotExist() {
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  movies: [Movie]
                }
                type Movie @key(fields: "id") {
                  id: ID!
                  imdbCode: String @shareable
                }
                """);

            Subgraph recommendations = subgraph("recommendations", """
                type Movie @key(fields: "id") {
                  id: ID!
                  similar(code: String @require(field: "<NonExistentType>.imdbCode")): [Movie]
                }
                """);

            assertInvalidWithMessage(validate(catalog, recommendations), "NonExistentType");
        }

        @Test
        void valid_infixTypeCondition() {
            // Infix type condition: media<Movie>.imdbCode
            // The 'media' field returns Media interface, narrowed to Movie type
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  content: [Content]
                }
                type Content @key(fields: "id") {
                  id: ID!
                  media: Media @shareable
                }
                interface Media {
                  id: ID!
                }
                type Movie implements Media {
                  id: ID!
                  imdbCode: String @shareable
                }
                type TVShow implements Media {
                  id: ID!
                  tvdbCode: String @shareable
                }
                """);

            Subgraph recommendations = subgraph("recommendations", """
                type Content @key(fields: "id") {
                  id: ID!
                  similarMovies(code: String @require(field: "media<Movie>.imdbCode")): [Content]
                }
                """);

            assertValid(validate(catalog, recommendations));
        }

        @Test
        void invalid_infixTypeCondition_fieldDoesNotExist() {
            // media<Movie>.nonExistent - field doesn't exist on narrowed type
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  content: [Content]
                }
                type Content @key(fields: "id") {
                  id: ID!
                  media: Media @shareable
                }
                interface Media {
                  id: ID!
                }
                type Movie implements Media {
                  id: ID!
                  imdbCode: String @shareable
                }
                """);

            Subgraph recommendations = subgraph("recommendations", """
                type Content @key(fields: "id") {
                  id: ID!
                  similar(code: String @require(field: "media<Movie>.nonExistent")): [Content]
                }
                """);

            assertInvalidWithMessage(validate(catalog, recommendations), "nonExistent");
        }

        @Test
        void invalid_infixTypeCondition_typeDoesNotExist() {
            // media<NonExistentType>.field - narrowed type doesn't exist
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  content: [Content]
                }
                type Content @key(fields: "id") {
                  id: ID!
                  media: Media @shareable
                }
                interface Media {
                  id: ID!
                }
                type Movie implements Media {
                  id: ID!
                  imdbCode: String @shareable
                }
                """);

            Subgraph recommendations = subgraph("recommendations", """
                type Content @key(fields: "id") {
                  id: ID!
                  similar(code: String @require(field: "media<NonExistentType>.imdbCode")): [Content]
                }
                """);

            assertInvalidWithMessage(validate(catalog, recommendations), "NonExistentType");
        }

        @Test
        void valid_chainedInfixTypeConditions() {
            // Chained infix: content<Article>.author<Person>.name
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  items: [Item]
                }
                type Item @key(fields: "id") {
                  id: ID!
                  content: Content @shareable
                }
                interface Content {
                  id: ID!
                }
                type Article implements Content {
                  id: ID!
                  author: Author @shareable
                }
                interface Author {
                  id: ID!
                }
                type Person implements Author {
                  id: ID!
                  name: String @shareable
                }
                """);

            Subgraph recommendations = subgraph("recommendations", """
                type Item @key(fields: "id") {
                  id: ID!
                  byAuthor(name: String @require(field: "content<Article>.author<Person>.name")): [Item]
                }
                """);

            assertValid(validate(catalog, recommendations));
        }

        @Test
        void valid_initialPlusInfixTypeCondition() {
            // Combined: <Content>.media<Movie>.imdbCode
            // Initial type condition sets context, infix narrows return type
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  items: [Item]
                }
                interface Item {
                  id: ID!
                }
                type Content implements Item @key(fields: "id") {
                  id: ID!
                  media: Media @shareable
                }
                interface Media {
                  id: ID!
                }
                type Movie implements Media {
                  id: ID!
                  imdbCode: String @shareable
                }
                """);

            Subgraph recommendations = subgraph("recommendations", """
                type Content @key(fields: "id") {
                  id: ID!
                  similar(code: String @require(field: "<Content>.media<Movie>.imdbCode")): [Content]
                }
                """);

            assertValid(validate(catalog, recommendations));
        }

        @Test
        void valid_multipleTypeConditions_onPath() {
            // Test type conditions on a nested path where each segment specifies the type
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  movies: [Movie]
                }
                type Movie @key(fields: "id") {
                  id: ID!
                  creator: Person @shareable
                }
                type Person @shareable {
                  name: String @shareable
                }
                """);

            Subgraph recommendations = subgraph("recommendations", """
                type Movie @key(fields: "id") {
                  id: ID!
                  byCreator(name: String @require(field: "creator.name")): [Movie]
                }
                """);

            assertValid(validate(catalog, recommendations));
        }
    }

    // ========================================================================
    // Object Selections
    // ========================================================================

    @Nested
    class ObjectSelections {

        @Test
        void valid_objectShorthand() {
            Subgraph products = subgraph("products", """
                type Query {
                  products: [Product]
                }
                type Product @key(fields: "id") {
                  id: ID!
                  width: Float @shareable
                  height: Float @shareable
                }
                """);

            Subgraph shipping = subgraph("shipping", """
                type Product @key(fields: "id") {
                  id: ID!
                  shippingCost(dim: DimInput @require(field: "{ width height }")): Float
                }
                input DimInput {
                  width: Float
                  height: Float
                }
                """);

            assertValid(validate(products, shipping));
        }

        @Test
        void invalid_objectShorthand_oneFieldMissing() {
            Subgraph products = subgraph("products", """
                type Query {
                  products: [Product]
                }
                type Product @key(fields: "id") {
                  id: ID!
                  width: Float @shareable
                }
                """);

            Subgraph shipping = subgraph("shipping", """
                type Product @key(fields: "id") {
                  id: ID!
                  shippingCost(dim: DimInput @require(field: "{ width height }")): Float
                }
                input DimInput {
                  width: Float
                  height: Float
                }
                """);

            assertInvalidWithMessage(validate(products, shipping), "height");
        }

        @Test
        void valid_objectExplicitMapping() {
            Subgraph products = subgraph("products", """
                type Query {
                  products: [Product]
                }
                type Product @key(fields: "id") {
                  id: ID!
                  productWidth: Float @shareable
                  productHeight: Float @shareable
                }
                """);

            Subgraph shipping = subgraph("shipping", """
                type Product @key(fields: "id") {
                  id: ID!
                  shippingCost(dim: DimInput @require(field: "{ w: productWidth, h: productHeight }")): Float
                }
                input DimInput {
                  w: Float
                  h: Float
                }
                """);

            assertValid(validate(products, shipping));
        }

        @Test
        void valid_pathPrefixedObjectShorthand() {
            Subgraph products = subgraph("products", """
                type Query {
                  products: [Product]
                }
                type Product @key(fields: "id") {
                  id: ID!
                  dimension: Dimension @shareable
                }
                type Dimension @shareable {
                  width: Float @shareable
                  height: Float @shareable
                  weight: Float @shareable
                }
                """);

            Subgraph shipping = subgraph("shipping", """
                type Product @key(fields: "id") {
                  id: ID!
                  shippingCost(dim: DimInput @require(field: "dimension.{ width height weight }")): Float
                }
                input DimInput {
                  width: Float
                  height: Float
                  weight: Float
                }
                """);

            assertValid(validate(products, shipping));
        }

        @Test
        void invalid_pathPrefixedObject_prefixMissing() {
            Subgraph products = subgraph("products", """
                type Query {
                  products: [Product]
                }
                type Product @key(fields: "id") {
                  id: ID!
                }
                """);

            Subgraph shipping = subgraph("shipping", """
                type Product @key(fields: "id") {
                  id: ID!
                  shippingCost(dim: DimInput @require(field: "dimension.{ width height }")): Float
                }
                input DimInput {
                  width: Float
                  height: Float
                }
                """);

            assertInvalidWithMessage(validate(products, shipping), "dimension");
        }

        @Test
        void valid_pathPrefixedObjectExplicit() {
            Subgraph products = subgraph("products", """
                type Query {
                  products: [Product]
                }
                type Product @key(fields: "id") {
                  id: ID!
                  addr: Address @shareable
                }
                type Address @shareable {
                  city: String @shareable
                  zip: String @shareable
                }
                """);

            Subgraph shipping = subgraph("shipping", """
                type Product @key(fields: "id") {
                  id: ID!
                  deliveryZone(loc: LocationInput @require(field: "addr.{ c: city, z: zip }")): String
                }
                input LocationInput {
                  c: String
                  z: String
                }
                """);

            assertValid(validate(products, shipping));
        }

        @Test
        void valid_nestedObjectSelection() {
            Subgraph products = subgraph("products", """
                type Query {
                  products: [Product]
                }
                type Product @key(fields: "id") {
                  id: ID!
                  weight: Float @shareable
                  dimension: Dimension @shareable
                }
                type Dimension @shareable {
                  width: Float @shareable
                  height: Float @shareable
                }
                """);

            Subgraph shipping = subgraph("shipping", """
                type Product @key(fields: "id") {
                  id: ID!
                  shippingCost(pkg: PackageInput @require(field: "{ weight, dimension: dimension.{ width height } }")): Float
                }
                input PackageInput {
                  weight: Float
                  dimension: DimInput
                }
                input DimInput {
                  width: Float
                  height: Float
                }
                """);

            assertValid(validate(products, shipping));
        }
    }

    // ========================================================================
    // List Selections
    // ========================================================================

    @Nested
    class ListSelections {

        @Test
        void valid_listWithSimplePath() {
            Subgraph orders = subgraph("orders", """
                type Query {
                  orders: [Order]
                }
                type Order @key(fields: "id") {
                  id: ID!
                  items: [Item] @shareable
                }
                type Item @shareable {
                  productId: ID @shareable
                }
                """);

            Subgraph inventory = subgraph("inventory", """
                type Order @key(fields: "id") {
                  id: ID!
                  checkStock(productIds: [ID] @require(field: "items[productId]")): Boolean
                }
                """);

            assertValid(validate(orders, inventory));
        }

        @Test
        void invalid_listWithSimplePath_fieldMissing() {
            Subgraph orders = subgraph("orders", """
                type Query {
                  orders: [Order]
                }
                type Order @key(fields: "id") {
                  id: ID!
                  items: [Item] @shareable
                }
                type Item @shareable {
                  name: String @shareable
                }
                """);

            Subgraph inventory = subgraph("inventory", """
                type Order @key(fields: "id") {
                  id: ID!
                  checkStock(productIds: [ID] @require(field: "items[productId]")): Boolean
                }
                """);

            assertInvalidWithMessage(validate(orders, inventory), "productId");
        }

        @Test
        void valid_listWithNestedPath() {
            Subgraph orders = subgraph("orders", """
                type Query {
                  orders: [Order]
                }
                type Order @key(fields: "id") {
                  id: ID!
                  items: [Item] @shareable
                }
                type Item @shareable {
                  product: Product @shareable
                }
                type Product @shareable {
                  sku: String @shareable
                }
                """);

            Subgraph inventory = subgraph("inventory", """
                type Order @key(fields: "id") {
                  id: ID!
                  checkStock(skus: [String] @require(field: "items[product.sku]")): Boolean
                }
                """);

            assertValid(validate(orders, inventory));
        }

        @Test
        void valid_listWithObjectElement() {
            Subgraph orders = subgraph("orders", """
                type Query {
                  orders: [Order]
                }
                type Order @key(fields: "id") {
                  id: ID!
                  items: [Item] @shareable
                }
                type Item @shareable {
                  productId: ID @shareable
                  quantity: Int @shareable
                }
                """);

            Subgraph inventory = subgraph("inventory", """
                type Order @key(fields: "id") {
                  id: ID!
                  checkStock(items: [ItemInput] @require(field: "items[{ pid: productId, qty: quantity }]")): Boolean
                }
                input ItemInput {
                  pid: ID
                  qty: Int
                }
                """);

            assertValid(validate(orders, inventory));
        }

        @Test
        void invalid_listWithObjectElement_oneFieldMissing() {
            Subgraph orders = subgraph("orders", """
                type Query {
                  orders: [Order]
                }
                type Order @key(fields: "id") {
                  id: ID!
                  items: [Item] @shareable
                }
                type Item @shareable {
                  productId: ID @shareable
                }
                """);

            Subgraph inventory = subgraph("inventory", """
                type Order @key(fields: "id") {
                  id: ID!
                  checkStock(items: [ItemInput] @require(field: "items[{ pid: productId, qty: quantity }]")): Boolean
                }
                input ItemInput {
                  pid: ID
                  qty: Int
                }
                """);

            assertInvalidWithMessage(validate(orders, inventory), "quantity");
        }

        @Test
        void valid_listWithPathPrefixedObject() {
            Subgraph orders = subgraph("orders", """
                type Query {
                  orders: [Order]
                }
                type Order @key(fields: "id") {
                  id: ID!
                  items: [Item] @shareable
                }
                type Item @shareable {
                  product: Product @shareable
                }
                type Product @shareable {
                  sku: String @shareable
                  price: Float @shareable
                }
                """);

            Subgraph inventory = subgraph("inventory", """
                type Order @key(fields: "id") {
                  id: ID!
                  total(items: [ItemInput] @require(field: "items[product.{ sku price }]")): Float
                }
                input ItemInput {
                  sku: String
                  price: Float
                }
                """);

            assertValid(validate(orders, inventory));
        }
    }

    // ========================================================================
    // Nested Lists
    // ========================================================================

    @Nested
    class NestedLists {

        @Test
        void valid_nestedList() {
            Subgraph data = subgraph("data", """
                type Query {
                  matrix: Matrix
                }
                type Matrix @key(fields: "id") {
                  id: ID!
                  rows: [[Cell]] @shareable
                }
                type Cell @shareable {
                  value: Int @shareable
                }
                """);

            Subgraph analytics = subgraph("analytics", """
                type Matrix @key(fields: "id") {
                  id: ID!
                  sum(values: [[Int]] @require(field: "rows[[value]]")): Int
                }
                """);

            assertValid(validate(data, analytics));
        }

        @Test
        void valid_nestedListWithObject() {
            Subgraph data = subgraph("data", """
                type Query {
                  groups: Groups
                }
                type Groups @key(fields: "id") {
                  id: ID!
                  data: [[Item]] @shareable
                }
                type Item @shareable {
                  id: ID @shareable
                  name: String @shareable
                }
                """);

            Subgraph analytics = subgraph("analytics", """
                type Groups @key(fields: "id") {
                  id: ID!
                  process(items: [[ItemInput]] @require(field: "data[[{ id name }]]")): String
                }
                input ItemInput {
                  id: ID
                  name: String
                }
                """);

            assertValid(validate(data, analytics));
        }

        @Test
        void valid_tripleNestedList() {
            Subgraph data = subgraph("data", """
                type Query {
                  cube: Cube
                }
                type Cube @key(fields: "id") {
                  id: ID!
                  values: [[[Int]]] @shareable
                }
                """);

            Subgraph analytics = subgraph("analytics", """
                type Cube @key(fields: "id") {
                  id: ID!
                  sum(vals: [[[Int]]] @require(field: "values")): Int
                }
                """);

            assertValid(validate(data, analytics));
        }
    }

    // ========================================================================
    // Alternatives
    // ========================================================================

    @Nested
    class Alternatives {

        @Test
        void valid_simpleAlternatives_sameType() {
            // Alternatives without type conditions - both fields must exist on the same type
            Subgraph products = subgraph("products", """
                type Query {
                  products: [Product]
                }
                type Product @key(fields: "id") {
                  id: ID!
                  sku: String @shareable
                  barcode: String @shareable
                }
                """);

            Subgraph inventory = subgraph("inventory", """
                type Product @key(fields: "id") {
                  id: ID!
                  lookup(code: String @require(field: "sku | barcode")): Product
                }
                """);

            assertValid(validate(products, inventory));
        }

        @Test
        void valid_alternativesWithPaths_sameBaseType() {
            // Alternatives with paths - different paths on the same type
            Subgraph products = subgraph("products", """
                type Query {
                  products: [Product]
                }
                type Product @key(fields: "id") {
                  id: ID!
                  primary: Identifier @shareable
                  secondary: Identifier @shareable
                }
                type Identifier @shareable {
                  code: String @shareable
                }
                """);

            Subgraph inventory = subgraph("inventory", """
                type Product @key(fields: "id") {
                  id: ID!
                  lookup(code: String @require(field: "primary.code | secondary.code")): Product
                }
                """);

            assertValid(validate(products, inventory));
        }

        @Test
        void valid_alternativesWithTypeConditions() {
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  media: [Media]
                }
                interface Media {
                  id: ID!
                }
                type Book implements Media @key(fields: "id") {
                  id: ID!
                  isbn: String @shareable
                }
                type Movie implements Media @key(fields: "id") {
                  id: ID!
                  imdbCode: String @shareable
                }
                """);

            Subgraph search = subgraph("search", """
                interface Media {
                  id: ID!
                }
                type Book implements Media @key(fields: "id") {
                  id: ID!
                  lookup(code: String @require(field: "<Book>.isbn | <Movie>.imdbCode")): Media
                }
                type Movie implements Media @key(fields: "id") {
                  id: ID!
                }
                """);

            assertValid(validate(catalog, search));
        }

        @Test
        void valid_alternativesWithInitialTypeConditions() {
            // Alternatives with initial type conditions - each alternative specifies the type
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  media: [Media]
                }
                interface Media {
                  id: ID!
                }
                type Book implements Media @key(fields: "id") {
                  id: ID!
                  isbn: String @shareable
                }
                type Movie implements Media @key(fields: "id") {
                  id: ID!
                  imdbCode: String @shareable
                }
                """);

            Subgraph search = subgraph("search", """
                interface Media {
                  id: ID!
                }
                type Book implements Media @key(fields: "id") {
                  id: ID!
                  lookup(code: String @require(field: "<Book>.isbn | <Movie>.imdbCode")): Media
                }
                type Movie implements Media @key(fields: "id") {
                  id: ID!
                }
                """);

            assertValid(validate(catalog, search));
        }

        @Test
        void valid_objectAlternativesWithTypeConditions() {
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  media: [Media]
                }
                interface Media {
                  id: ID!
                }
                type Book implements Media @key(fields: "id") {
                  id: ID!
                  isbn: String @shareable
                }
                type Movie implements Media @key(fields: "id") {
                  id: ID!
                  imdbId: String @shareable
                }
                """);

            Subgraph search = subgraph("search", """
                interface Media {
                  id: ID!
                }
                type Book implements Media @key(fields: "id") {
                  id: ID!
                  lookup(id: MediaIdInput @require(field: "{ bookId: <Book>.isbn } | { movieId: <Movie>.imdbId }")): Media
                }
                type Movie implements Media @key(fields: "id") {
                  id: ID!
                }
                input MediaIdInput {
                  bookId: String
                  movieId: String
                }
                """);

            assertValid(validate(catalog, search));
        }

        @Test
        void valid_nestedAlternativesInsideObject() {
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  media: [Media]
                }
                interface Media {
                  id: ID!
                }
                type Book implements Media @key(fields: "id") {
                  id: ID!
                  bookCode: String @shareable
                }
                type Movie implements Media @key(fields: "id") {
                  id: ID!
                  movieCode: String @shareable
                }
                """);

            Subgraph search = subgraph("search", """
                interface Media {
                  id: ID!
                }
                type Book implements Media @key(fields: "id") {
                  id: ID!
                  lookup(input: NestedInput @require(field: "{ nested: { bId: <Book>.bookCode } | { mId: <Movie>.movieCode } }")): Media
                }
                type Movie implements Media @key(fields: "id") {
                  id: ID!
                }
                input NestedInput {
                  nested: CodeInput
                }
                input CodeInput {
                  bId: String
                  mId: String
                }
                """);

            assertValid(validate(catalog, search));
        }
    }

    // ========================================================================
    // List with Type Conditions
    // ========================================================================

    @Nested
    class ListWithTypeConditions {

        @Test
        void valid_listWithTypeCondition() {
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  orders: [Order]
                }
                type Order @key(fields: "id") {
                  id: ID!
                  items: [Item] @shareable
                }
                interface Item {
                  id: ID!
                }
                type Product implements Item {
                  id: ID!
                  sku: String @shareable
                }
                """);

            Subgraph inventory = subgraph("inventory", """
                type Order @key(fields: "id") {
                  id: ID!
                  checkStock(skus: [String] @require(field: "items[<Product>.sku]")): Boolean
                }
                """);

            assertValid(validate(catalog, inventory));
        }
    }

    // ========================================================================
    // Complex Combinations
    // ========================================================================

    @Nested
    class ComplexCombinations {

        @Test
        void valid_complexNestedWithAlternatives() {
            // Alternatives inside a list - both alternatives are on the same element type
            Subgraph orders = subgraph("orders", """
                type Query {
                  orders: [Order]
                }
                type Order @key(fields: "id") {
                  id: ID!
                  customer: Customer @shareable
                }
                type Customer @shareable {
                  addresses: [Address] @shareable
                }
                type Address @shareable {
                  street: String @shareable
                  city: String @shareable
                  coords: String @shareable
                }
                """);

            Subgraph delivery = subgraph("delivery", """
                type Order @key(fields: "id") {
                  id: ID!
                  route(dest: [DestInput] @require(field: "customer.addresses[{ street city } | coords]")): String
                }
                input DestInput {
                  street: String
                  city: String
                }
                """);

            assertValid(validate(orders, delivery));
        }

        @Test
        void valid_deepPathPrefixWithList() {
            Subgraph orders = subgraph("orders", """
                type Query {
                  orders: [Order]
                }
                type Order @key(fields: "id") {
                  id: ID!
                  details: OrderDetails @shareable
                }
                type OrderDetails @shareable {
                  items: [Item] @shareable
                }
                type Item @shareable {
                  product: Product @shareable
                }
                type Product @shareable {
                  sku: String @shareable
                  price: Float @shareable
                }
                """);

            Subgraph inventory = subgraph("inventory", """
                type Order @key(fields: "id") {
                  id: ID!
                  total(items: [ItemInput] @require(field: "details.items[product.{ sku price }]")): Float
                }
                input ItemInput {
                  sku: String
                  price: Float
                }
                """);

            assertValid(validate(orders, inventory));
        }

        @Test
        void valid_coordinatesMapping() {
            Subgraph geo = subgraph("geo", """
                type Query {
                  locations: [Location]
                }
                type Location @key(fields: "id") {
                  id: ID!
                  coordinates: [Coordinate] @shareable
                }
                type Coordinate @shareable {
                  x: Int @shareable
                  y: Int @shareable
                }
                """);

            Subgraph maps = subgraph("maps", """
                type Location @key(fields: "id") {
                  id: ID!
                  route(loc: LocationInput @require(field: "{ coordinates: coordinates[{ lat: x, lon: y }] }")): String
                }
                input LocationInput {
                  coordinates: [PositionInput]
                }
                input PositionInput {
                  lat: Int
                  lon: Int
                }
                """);

            assertValid(validate(geo, maps));
        }
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Nested
    class EdgeCases {

        @Test
        void valid_fieldExistsInMultipleOtherSchemas() {
            Subgraph schemaA = subgraph("schemaA", """
                type Query {
                  products: [Product]
                }
                type Product @key(fields: "id") {
                  id: ID!
                  weight: Float @shareable
                }
                """);

            Subgraph schemaB = subgraph("schemaB", """
                type Product @key(fields: "id") {
                  id: ID!
                  weight: Float @shareable
                }
                """);

            Subgraph schemaC = subgraph("schemaC", """
                type Product @key(fields: "id") {
                  id: ID!
                  shippingCost(w: Float @require(field: "weight")): Float
                }
                """);

            assertValid(validate(schemaA, schemaB, schemaC));
        }

        @Test
        void valid_fieldOnInterface_implementedByMultipleTypes() {
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  media: [Media]
                }
                interface Media {
                  id: ID!
                  title: String @shareable
                }
                type Book implements Media @key(fields: "id") {
                  id: ID!
                  title: String @shareable
                }
                type Movie implements Media @key(fields: "id") {
                  id: ID!
                  title: String @shareable
                }
                """);

            Subgraph search = subgraph("search", """
                interface Media {
                  id: ID!
                }
                type Book implements Media @key(fields: "id") {
                  id: ID!
                  search(t: String @require(field: "title")): [Media]
                }
                type Movie implements Media @key(fields: "id") {
                  id: ID!
                }
                """);

            assertValid(validate(catalog, search));
        }

        @Test
        void invalid_allAlternativesMissing() {
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  media: [Media]
                }
                interface Media {
                  id: ID!
                }
                type Book implements Media @key(fields: "id") {
                  id: ID!
                }
                type Movie implements Media @key(fields: "id") {
                  id: ID!
                }
                """);

            Subgraph search = subgraph("search", """
                interface Media {
                  id: ID!
                }
                type Book implements Media @key(fields: "id") {
                  id: ID!
                  lookup(code: String @require(field: "<Book>.isbn | <Movie>.imdbCode")): Media
                }
                type Movie implements Media @key(fields: "id") {
                  id: ID!
                }
                """);

            // Both alternatives reference non-existent fields
            assertInvalid(validate(catalog, search));
        }

        @Test
        void valid_fieldWithUnderscores() {
            Subgraph products = subgraph("products", """
                type Query {
                  products: [Product]
                }
                type Product @key(fields: "id") {
                  id: ID!
                  _internal_weight: Float @shareable
                }
                """);

            Subgraph shipping = subgraph("shipping", """
                type Product @key(fields: "id") {
                  id: ID!
                  shippingCost(w: Float @require(field: "_internal_weight")): Float
                }
                """);

            assertValid(validate(products, shipping));
        }
    }

    // ========================================================================
    // Type Condition Coverage
    // When type conditions are used, all concrete types must be covered
    // if the argument is non-null. Nullable arguments allow partial coverage.
    // ========================================================================

    @Nested
    class TypeConditionCoverage {

        private static final String COVERAGE_CODE = "REQUIRE_INVALID_FIELDS";

        // ----------------------------------------------------------------------
        // Non-null arguments - must cover all types
        // ----------------------------------------------------------------------

        @Test
        void invalid_nonNullArg_incompleteTypeCoverage_interface() {
            // Media interface has Book and Movie, but only Movie is covered
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  content: [Content]
                }
                type Content @key(fields: "id") {
                  id: ID!
                  media: Media @shareable
                }
                interface Media {
                  id: ID!
                }
                type Book implements Media {
                  id: ID!
                  isbn: String @shareable
                }
                type Movie implements Media {
                  id: ID!
                  imdbCode: String @shareable
                }
                """);

            Subgraph recommendations = subgraph("recommendations", """
                type Content @key(fields: "id") {
                  id: ID!
                  similar(code: String! @require(field: "media<Movie>.imdbCode")): [Content]
                }
                """);

            // Should fail: code is non-null but Book type is not covered
            ValidationResult result = validate(catalog, recommendations);
            assertThat(result.hasErrors()).isTrue();
            assertThat(result.errors())
                .anyMatch(e -> e.message().contains("Book") || e.message().contains("not covered"));
        }

        @Test
        void invalid_nonNullArg_incompleteTypeCoverage_union() {
            // SearchResult union has Book, Movie, Author but only Book and Movie are covered
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  search: [SearchResult]
                }
                type Content @key(fields: "id") {
                  id: ID!
                  result: SearchResult @shareable
                }
                union SearchResult = Book | Movie | Author
                type Book {
                  isbn: String @shareable
                }
                type Movie {
                  imdbCode: String @shareable
                }
                type Author {
                  authorId: String @shareable
                }
                """);

            Subgraph recommendations = subgraph("recommendations", """
                type Content @key(fields: "id") {
                  id: ID!
                  related(code: String! @require(field: "result<Book>.isbn | result<Movie>.imdbCode")): [Content]
                }
                """);

            // Should fail: code is non-null but Author type is not covered
            ValidationResult result = validate(catalog, recommendations);
            assertThat(result.hasErrors()).isTrue();
            assertThat(result.errors())
                .anyMatch(e -> e.message().contains("Author") || e.message().contains("not covered"));
        }

        @Test
        void valid_nonNullArg_completeTypeCoverage_interface() {
            // All interface implementors are covered
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  content: [Content]
                }
                type Content @key(fields: "id") {
                  id: ID!
                  media: Media @shareable
                }
                interface Media {
                  id: ID!
                }
                type Book implements Media {
                  id: ID!
                  isbn: String @shareable
                }
                type Movie implements Media {
                  id: ID!
                  imdbCode: String @shareable
                }
                """);

            Subgraph recommendations = subgraph("recommendations", """
                type Content @key(fields: "id") {
                  id: ID!
                  similar(code: String! @require(field: "media<Book>.isbn | media<Movie>.imdbCode")): [Content]
                }
                """);

            // Should pass: both Book and Movie are covered
            assertValid(validate(catalog, recommendations));
        }

        @Test
        void valid_nonNullArg_completeTypeCoverage_union() {
            // All union members are covered
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  search: [SearchResult]
                }
                type Content @key(fields: "id") {
                  id: ID!
                  result: SearchResult @shareable
                }
                union SearchResult = Book | Movie
                type Book {
                  isbn: String @shareable
                }
                type Movie {
                  imdbCode: String @shareable
                }
                """);

            Subgraph recommendations = subgraph("recommendations", """
                type Content @key(fields: "id") {
                  id: ID!
                  related(code: String! @require(field: "<Book>.isbn | <Movie>.imdbCode")): [Content]
                }
                """);

            // Should pass: both Book and Movie are covered
            assertValid(validate(catalog, recommendations));
        }

        // ----------------------------------------------------------------------
        // Nullable arguments - partial coverage is allowed
        // ----------------------------------------------------------------------

        @Test
        void valid_nullableArg_incompleteTypeCoverage() {
            // Media has Book and Movie, but only Movie is covered - OK because arg is nullable
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  content: [Content]
                }
                type Content @key(fields: "id") {
                  id: ID!
                  media: Media @shareable
                }
                interface Media {
                  id: ID!
                }
                type Book implements Media {
                  id: ID!
                  isbn: String @shareable
                }
                type Movie implements Media {
                  id: ID!
                  imdbCode: String @shareable
                }
                """);

            Subgraph recommendations = subgraph("recommendations", """
                type Content @key(fields: "id") {
                  id: ID!
                  similar(code: String @require(field: "media<Movie>.imdbCode")): [Content]
                }
                """);

            // Should pass: code is nullable, so Book getting null is fine
            assertValid(validate(catalog, recommendations));
        }

        // ----------------------------------------------------------------------
        // Input object fields - recursive nullability check
        // ----------------------------------------------------------------------

        @Test
        void invalid_nonNullInputField_incompleteTypeCoverage() {
            // Input field is non-null but type coverage is incomplete
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  content: [Content]
                }
                type Content @key(fields: "id") {
                  id: ID!
                  media: Media @shareable
                }
                interface Media {
                  id: ID!
                }
                type Book implements Media {
                  id: ID!
                  isbn: String @shareable
                }
                type Movie implements Media {
                  id: ID!
                  imdbCode: String @shareable
                }
                """);

            Subgraph recommendations = subgraph("recommendations", """
                type Content @key(fields: "id") {
                  id: ID!
                  similar(input: MediaInput! @require(field: "{ code: media<Movie>.imdbCode }")): [Content]
                }
                input MediaInput {
                  code: String!
                }
                """);

            // Should fail: code field in input is non-null but Book is not covered
            ValidationResult result = validate(catalog, recommendations);
            assertThat(result.hasErrors()).isTrue();
        }

        @Test
        void valid_nullableInputField_incompleteTypeCoverage() {
            // Input field is nullable so incomplete coverage is OK
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  content: [Content]
                }
                type Content @key(fields: "id") {
                  id: ID!
                  media: Media @shareable
                }
                interface Media {
                  id: ID!
                }
                type Book implements Media {
                  id: ID!
                  isbn: String @shareable
                }
                type Movie implements Media {
                  id: ID!
                  imdbCode: String @shareable
                }
                """);

            Subgraph recommendations = subgraph("recommendations", """
                type Content @key(fields: "id") {
                  id: ID!
                  similar(input: MediaInput! @require(field: "{ code: media<Movie>.imdbCode }")): [Content]
                }
                input MediaInput {
                  code: String
                }
                """);

            // Should pass: code field in input is nullable
            assertValid(validate(catalog, recommendations));
        }

        @Test
        void valid_mixedNullability_inputObject() {
            // Some fields non-null (fully covered), some nullable (partially covered)
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  content: [Content]
                }
                type Content @key(fields: "id") {
                  id: ID!
                  media: Media @shareable
                }
                interface Media {
                  id: ID!
                }
                type Book implements Media {
                  id: ID!
                  isbn: String @shareable
                  title: String @shareable
                }
                type Movie implements Media {
                  id: ID!
                  imdbCode: String @shareable
                  title: String @shareable
                }
                """);

            Subgraph recommendations = subgraph("recommendations", """
                type Content @key(fields: "id") {
                  id: ID!
                  similar(input: MediaInput! @require(field: "{ code: media<Book>.isbn | media<Movie>.imdbCode, extra: media<Movie>.title }")): [Content]
                }
                input MediaInput {
                  code: String!
                  extra: String
                }
                """);

            // Should pass: code is non-null and fully covered, extra is nullable and partially covered
            assertValid(validate(catalog, recommendations));
        }

        @Test
        void invalid_mixedNullability_inputObject_nonNullNotCovered() {
            // Non-null field is not fully covered
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  content: [Content]
                }
                type Content @key(fields: "id") {
                  id: ID!
                  media: Media @shareable
                }
                interface Media {
                  id: ID!
                }
                type Book implements Media {
                  id: ID!
                  isbn: String @shareable
                }
                type Movie implements Media {
                  id: ID!
                  imdbCode: String @shareable
                }
                """);

            Subgraph recommendations = subgraph("recommendations", """
                type Content @key(fields: "id") {
                  id: ID!
                  similar(input: MediaInput! @require(field: "{ code: media<Movie>.imdbCode, extra: media<Book>.isbn | media<Movie>.imdbCode }")): [Content]
                }
                input MediaInput {
                  code: String!
                  extra: String!
                }
                """);

            // Should fail: code is non-null but only Movie is covered (Book missing)
            ValidationResult result = validate(catalog, recommendations);
            assertThat(result.hasErrors()).isTrue();
        }

        // ----------------------------------------------------------------------
        // No type conditions - should not trigger this validation
        // ----------------------------------------------------------------------

        @Test
        void valid_noTypeConditions_nonNullArg() {
            // No type conditions used, so coverage check doesn't apply
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  products: [Product]
                }
                type Product @key(fields: "id") {
                  id: ID!
                  weight: Float @shareable
                }
                """);

            Subgraph shipping = subgraph("shipping", """
                type Product @key(fields: "id") {
                  id: ID!
                  shippingCost(w: Float! @require(field: "weight")): Float
                }
                """);

            assertValid(validate(catalog, shipping));
        }

        // ----------------------------------------------------------------------
        // Initial type condition on declaring type
        // ----------------------------------------------------------------------

        @Test
        void invalid_initialTypeCondition_incompleteOnDeclaringType() {
            // @require on Item field, but only Content subtype is covered
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  items: [Item]
                }
                interface Item {
                  id: ID!
                }
                type Content implements Item {
                  id: ID!
                  title: String @shareable
                }
                type Product implements Item {
                  id: ID!
                  name: String @shareable
                }
                """);

            Subgraph recommendations = subgraph("recommendations", """
                interface Item {
                  id: ID!
                }
                type Content implements Item @key(fields: "id") {
                  id: ID!
                  related(title: String! @require(field: "<Content>.title")): [Item]
                }
                type Product implements Item @key(fields: "id") {
                  id: ID!
                }
                """);

            // Should fail: title is non-null, initial type condition only covers Content,
            // but the field is on Content so only Content instances will use this field
            // Actually this should PASS because the @require is on Content.related,
            // so it only applies to Content instances, not Product
            assertValid(validate(catalog, recommendations));
        }

        // ----------------------------------------------------------------------
        // Infix type condition - field returns abstract type
        // ----------------------------------------------------------------------

        @Test
        void invalid_infixTypeCondition_fieldReturnsInterface() {
            // field.media returns Media interface, narrowed to Movie only
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  content: [Content]
                }
                type Content @key(fields: "id") {
                  id: ID!
                  media: Media @shareable
                }
                interface Media {
                  id: ID!
                }
                type Book implements Media {
                  id: ID!
                  isbn: String @shareable
                }
                type Movie implements Media {
                  id: ID!
                  imdbCode: String @shareable
                }
                type TVShow implements Media {
                  id: ID!
                  tvdbCode: String @shareable
                }
                """);

            Subgraph recommendations = subgraph("recommendations", """
                type Content @key(fields: "id") {
                  id: ID!
                  similar(code: String! @require(field: "media<Movie>.imdbCode | media<Book>.isbn")): [Content]
                }
                """);

            // Should fail: Media has Book, Movie, TVShow but only Movie and Book are covered
            ValidationResult result = validate(catalog, recommendations);
            assertThat(result.hasErrors()).isTrue();
            assertThat(result.errors())
                .anyMatch(e -> e.message().contains("TVShow") || e.message().contains("not covered"));
        }

        // ----------------------------------------------------------------------
        // Nullable input fields - incomplete coverage is OK
        // ----------------------------------------------------------------------

        @Test
        void valid_nullableInputFields_incompleteTypeCoverage() {
            // With nullable input fields, incomplete coverage is OK - unmatched types get null
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  content: [Content]
                }
                type Content @key(fields: "id") {
                  id: ID!
                  media: Media @shareable
                }
                interface Media {
                  id: ID!
                }
                type Book implements Media {
                  id: ID!
                  isbn: String @shareable
                }
                type Movie implements Media {
                  id: ID!
                  imdbCode: String @shareable
                }
                """);

            Subgraph recommendations = subgraph("recommendations", """
                type Content @key(fields: "id") {
                  id: ID!
                  lookup(input: MediaLookup! @require(field: "{ bookId: media<Book>.isbn, movieId: media<Movie>.imdbCode }")): Media
                }
                interface Media {
                  id: ID!
                }
                input MediaLookup {
                  bookId: String
                  movieId: String
                }
                """);

            // Should pass: both fields are nullable, so partial coverage is OK for each
            assertValid(validate(catalog, recommendations));
        }

        // ----------------------------------------------------------------------
        // Chained type conditions
        // ----------------------------------------------------------------------

        @Test
        void invalid_chainedTypeConditions_incompleteAtSecondLevel() {
            // content<Article>.author<Person>.name - Author has Person and Organization
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  items: [Item]
                }
                type Item @key(fields: "id") {
                  id: ID!
                  content: Content @shareable
                }
                interface Content {
                  id: ID!
                }
                type Article implements Content {
                  id: ID!
                  author: Author @shareable
                }
                interface Author {
                  id: ID!
                }
                type Person implements Author {
                  id: ID!
                  name: String @shareable
                }
                type Organization implements Author {
                  id: ID!
                  orgName: String @shareable
                }
                """);

            Subgraph recommendations = subgraph("recommendations", """
                type Item @key(fields: "id") {
                  id: ID!
                  byAuthor(name: String! @require(field: "content<Article>.author<Person>.name")): [Item]
                }
                """);

            // Should fail: Author has Person and Organization, but only Person is covered
            ValidationResult result = validate(catalog, recommendations);
            assertThat(result.hasErrors()).isTrue();
        }

        @Test
        void valid_chainedTypeConditions_fullyConvered() {
            // All types at each level are covered
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  items: [Item]
                }
                type Item @key(fields: "id") {
                  id: ID!
                  content: Content @shareable
                }
                interface Content {
                  id: ID!
                }
                type Article implements Content {
                  id: ID!
                  author: Author @shareable
                }
                type Video implements Content {
                  id: ID!
                  creator: String @shareable
                }
                interface Author {
                  id: ID!
                }
                type Person implements Author {
                  id: ID!
                  name: String @shareable
                }
                type Organization implements Author {
                  id: ID!
                  name: String @shareable
                }
                """);

            Subgraph recommendations = subgraph("recommendations", """
                type Item @key(fields: "id") {
                  id: ID!
                  byCreator(name: String! @require(field: "content<Article>.author<Person>.name | content<Article>.author<Organization>.name | content<Video>.creator")): [Item]
                }
                """);

            // Should pass: All combinations are covered
            assertValid(validate(catalog, recommendations));
        }

        // ----------------------------------------------------------------------
        // Complex combinations: type conditions + lists + nested lists + objects
        // ----------------------------------------------------------------------

        @Test
        void invalid_listWithTypeConditions_incompleteForNonNull() {
            // items[<Book>.isbn | <Movie>.imdbCode] - list element uses type conditions
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  orders: [Order]
                }
                type Order @key(fields: "id") {
                  id: ID!
                  items: [Media] @shareable
                }
                interface Media {
                  id: ID!
                }
                type Book implements Media {
                  id: ID!
                  isbn: String @shareable
                }
                type Movie implements Media {
                  id: ID!
                  imdbCode: String @shareable
                }
                type Album implements Media {
                  id: ID!
                  catalogNumber: String @shareable
                }
                """);

            Subgraph inventory = subgraph("inventory", """
                type Order @key(fields: "id") {
                  id: ID!
                  checkCodes(codes: [String!]! @require(field: "items[<Book>.isbn | <Movie>.imdbCode]")): Boolean
                }
                """);

            // Should fail: codes is non-null list of non-null, but Album is not covered
            ValidationResult result = validate(catalog, inventory);
            assertThat(result.hasErrors()).isTrue();
            assertThat(result.errors())
                .anyMatch(e -> e.message().contains("Album") || e.message().contains("not covered"));
        }

        @Test
        void valid_listWithTypeConditions_completeForNonNull() {
            // All types covered in list element type conditions
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  orders: [Order]
                }
                type Order @key(fields: "id") {
                  id: ID!
                  items: [Media] @shareable
                }
                interface Media {
                  id: ID!
                }
                type Book implements Media {
                  id: ID!
                  isbn: String @shareable
                }
                type Movie implements Media {
                  id: ID!
                  imdbCode: String @shareable
                }
                """);

            Subgraph inventory = subgraph("inventory", """
                type Order @key(fields: "id") {
                  id: ID!
                  checkCodes(codes: [String!]! @require(field: "items[<Book>.isbn | <Movie>.imdbCode]")): Boolean
                }
                """);

            // Should pass: all Media types (Book, Movie) are covered
            assertValid(validate(catalog, inventory));
        }

        @Test
        void valid_listWithTypeConditions_nullableAllowsPartialCoverage() {
            // Nullable list element allows partial type coverage
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  orders: [Order]
                }
                type Order @key(fields: "id") {
                  id: ID!
                  items: [Media] @shareable
                }
                interface Media {
                  id: ID!
                }
                type Book implements Media {
                  id: ID!
                  isbn: String @shareable
                }
                type Movie implements Media {
                  id: ID!
                  imdbCode: String @shareable
                }
                type Album implements Media {
                  id: ID!
                  catalogNumber: String @shareable
                }
                """);

            Subgraph inventory = subgraph("inventory", """
                type Order @key(fields: "id") {
                  id: ID!
                  checkCodes(codes: [String] @require(field: "items[<Book>.isbn | <Movie>.imdbCode]")): Boolean
                }
                """);

            // Should pass: codes is nullable, so Album getting null is fine
            assertValid(validate(catalog, inventory));
        }

        @Test
        void invalid_nestedListWithTypeConditions_incompleteForNonNull() {
            // matrix[[<Book>.isbn]] - nested list with type condition
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  warehouse: Warehouse
                }
                type Warehouse @key(fields: "id") {
                  id: ID!
                  shelves: [[Media]] @shareable
                }
                interface Media {
                  id: ID!
                }
                type Book implements Media {
                  id: ID!
                  isbn: String @shareable
                }
                type Movie implements Media {
                  id: ID!
                  imdbCode: String @shareable
                }
                """);

            Subgraph inventory = subgraph("inventory", """
                type Warehouse @key(fields: "id") {
                  id: ID!
                  allIsbns(codes: [[String!]!]! @require(field: "shelves[[<Book>.isbn]]")): Int
                }
                """);

            // Should fail: only Book is covered, Movie is missing
            ValidationResult result = validate(catalog, inventory);
            assertThat(result.hasErrors()).isTrue();
            assertThat(result.errors())
                .anyMatch(e -> e.message().contains("Movie") || e.message().contains("not covered"));
        }

        @Test
        void valid_nestedListWithTypeConditions_complete() {
            // All types covered in nested list
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  warehouse: Warehouse
                }
                type Warehouse @key(fields: "id") {
                  id: ID!
                  shelves: [[Media]] @shareable
                }
                interface Media {
                  id: ID!
                }
                type Book implements Media {
                  id: ID!
                  isbn: String @shareable
                }
                type Movie implements Media {
                  id: ID!
                  imdbCode: String @shareable
                }
                """);

            Subgraph inventory = subgraph("inventory", """
                type Warehouse @key(fields: "id") {
                  id: ID!
                  allCodes(codes: [[String!]!]! @require(field: "shelves[[<Book>.isbn | <Movie>.imdbCode]]")): Int
                }
                """);

            // Should pass: both Book and Movie are covered
            assertValid(validate(catalog, inventory));
        }

        @Test
        void invalid_objectInsideListWithTypeConditions_nonNullFieldIncomplete() {
            // items[{ code: <Book>.isbn }] - object selection with non-null field inside list
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  cart: Cart
                }
                type Cart @key(fields: "id") {
                  id: ID!
                  items: [Media] @shareable
                }
                interface Media {
                  id: ID!
                }
                type Book implements Media {
                  id: ID!
                  isbn: String @shareable
                }
                type Movie implements Media {
                  id: ID!
                  imdbCode: String @shareable
                }
                """);

            Subgraph checkout = subgraph("checkout", """
                type Cart @key(fields: "id") {
                  id: ID!
                  process(items: [MediaInput!]! @require(field: "items[{ code: <Book>.isbn }]")): Boolean
                }
                input MediaInput {
                  code: String!
                }
                """);

            // Should fail: code is non-null but only Book is covered, Movie has no value
            ValidationResult result = validate(catalog, checkout);
            assertThat(result.hasErrors()).isTrue();
            assertThat(result.errors())
                .anyMatch(e -> e.message().contains("Movie") || e.message().contains("not covered"));
        }

        @Test
        void valid_objectInsideListWithTypeConditions_nullableFieldsPartialCoverage() {
            // items[{ bookId: <Book>.isbn, movieId: <Movie>.imdbCode }] with nullable fields
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  cart: Cart
                }
                type Cart @key(fields: "id") {
                  id: ID!
                  items: [Media] @shareable
                }
                interface Media {
                  id: ID!
                }
                type Book implements Media {
                  id: ID!
                  isbn: String @shareable
                }
                type Movie implements Media {
                  id: ID!
                  imdbCode: String @shareable
                }
                type Game implements Media {
                  id: ID!
                  gameCode: String @shareable
                }
                """);

            Subgraph checkout = subgraph("checkout", """
                type Cart @key(fields: "id") {
                  id: ID!
                  process(items: [MediaInput!]! @require(field: "items[{ bookId: <Book>.isbn, movieId: <Movie>.imdbCode }]")): Boolean
                }
                input MediaInput {
                  bookId: String
                  movieId: String
                }
                """);

            // Should pass: both fields are nullable, so Game getting null for both is acceptable
            // Each field individually has partial coverage, but that's OK for nullable fields
            assertValid(validate(catalog, checkout));
        }

        @Test
        void valid_objectInsideListWithTypeConditions_allTypesHaveValue() {
            // Each type has at least one field covered
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  cart: Cart
                }
                type Cart @key(fields: "id") {
                  id: ID!
                  items: [Media] @shareable
                }
                interface Media {
                  id: ID!
                }
                type Book implements Media {
                  id: ID!
                  isbn: String @shareable
                }
                type Movie implements Media {
                  id: ID!
                  imdbCode: String @shareable
                }
                """);

            Subgraph checkout = subgraph("checkout", """
                type Cart @key(fields: "id") {
                  id: ID!
                  process(items: [MediaInput!]! @require(field: "items[{ bookId: <Book>.isbn, movieId: <Movie>.imdbCode }]")): Boolean
                }
                input MediaInput {
                  bookId: String
                  movieId: String
                }
                """);

            // Should pass: Book gets bookId, Movie gets movieId
            assertValid(validate(catalog, checkout));
        }

        @Test
        void invalid_pathPrefixWithTypeCondition_nestedList_incomplete() {
            // media<TVShow>.seasons[[episodes[title]]] - path prefix with type condition + nested structure
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  content: [Content]
                }
                type Content @key(fields: "id") {
                  id: ID!
                  media: Media @shareable
                }
                interface Media {
                  id: ID!
                }
                type Movie implements Media {
                  id: ID!
                  title: String @shareable
                }
                type TVShow implements Media {
                  id: ID!
                  seasons: [[Episode]] @shareable
                }
                type Episode {
                  title: String @shareable
                }
                """);

            Subgraph analytics = subgraph("analytics", """
                type Content @key(fields: "id") {
                  id: ID!
                  episodeTitles(titles: [[String!]!]! @require(field: "media<TVShow>.seasons[[title]]")): Int
                }
                """);

            // Should fail: only TVShow is covered, Movie is not covered
            ValidationResult result = validate(catalog, analytics);
            assertThat(result.hasErrors()).isTrue();
            assertThat(result.errors())
                .anyMatch(e -> e.message().contains("Movie") || e.message().contains("not covered"));
        }

        @Test
        void valid_complexCombination_typeConditions_nestedLists_objects() {
            // Complex: type conditions + nested list + object selection all combined
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  library: Library
                }
                type Library @key(fields: "id") {
                  id: ID!
                  shelves: [[Media]] @shareable
                }
                interface Media {
                  id: ID!
                }
                type Book implements Media {
                  id: ID!
                  isbn: String @shareable
                  title: String @shareable
                }
                type Movie implements Media {
                  id: ID!
                  imdbCode: String @shareable
                  title: String @shareable
                }
                """);

            Subgraph analytics = subgraph("analytics", """
                type Library @key(fields: "id") {
                  id: ID!
                  analyze(data: [[MediaData!]!]! @require(field: "shelves[[{ code: <Book>.isbn | <Movie>.imdbCode, name: title }]]")): String
                }
                input MediaData {
                  code: String!
                  name: String!
                }
                """);

            // Should pass: code has complete coverage (Book|Movie), name uses common field
            assertValid(validate(catalog, analytics));
        }

        @Test
        void invalid_complexCombination_nonNullFieldMissingCoverage() {
            // Complex combination where a non-null field doesn't have complete coverage
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  library: Library
                }
                type Library @key(fields: "id") {
                  id: ID!
                  shelves: [[Media]] @shareable
                }
                interface Media {
                  id: ID!
                }
                type Book implements Media {
                  id: ID!
                  isbn: String @shareable
                  author: String @shareable
                }
                type Movie implements Media {
                  id: ID!
                  imdbCode: String @shareable
                  director: String @shareable
                }
                type Album implements Media {
                  id: ID!
                  catalogNumber: String @shareable
                  artist: String @shareable
                }
                """);

            Subgraph analytics = subgraph("analytics", """
                type Library @key(fields: "id") {
                  id: ID!
                  analyze(data: [[MediaData!]!]! @require(field: "shelves[[{ code: <Book>.isbn | <Movie>.imdbCode, creator: <Book>.author | <Movie>.director }]]")): String
                }
                input MediaData {
                  code: String!
                  creator: String!
                }
                """);

            // Should fail: Album is not covered in any field
            ValidationResult result = validate(catalog, analytics);
            assertThat(result.hasErrors()).isTrue();
            assertThat(result.errors())
                .anyMatch(e -> e.message().contains("Album") || e.message().contains("not covered"));
        }

        @Test
        void valid_tripleNestedListWithTypeConditions() {
            // [[[<Book>.isbn | <Movie>.imdbCode]]] - deeply nested list
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  warehouse: Warehouse
                }
                type Warehouse @key(fields: "id") {
                  id: ID!
                  aisles: [[[Media]]] @shareable
                }
                interface Media {
                  id: ID!
                }
                type Book implements Media {
                  id: ID!
                  isbn: String @shareable
                }
                type Movie implements Media {
                  id: ID!
                  imdbCode: String @shareable
                }
                """);

            Subgraph inventory = subgraph("inventory", """
                type Warehouse @key(fields: "id") {
                  id: ID!
                  allCodes(codes: [[[String!]!]!]! @require(field: "aisles[[[<Book>.isbn | <Movie>.imdbCode]]]")): Int
                }
                """);

            // Should pass: all types covered
            assertValid(validate(catalog, inventory));
        }

        @Test
        void invalid_alternativesWithDifferentStructures_incomplete() {
            // Alternatives that have different structures but incomplete type coverage
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  store: Store
                }
                type Store @key(fields: "id") {
                  id: ID!
                  inventory: [Media] @shareable
                }
                interface Media {
                  id: ID!
                }
                type Book implements Media {
                  id: ID!
                  isbn: String @shareable
                  pages: Int @shareable
                }
                type Movie implements Media {
                  id: ID!
                  imdbCode: String @shareable
                  duration: Int @shareable
                }
                type Music implements Media {
                  id: ID!
                  isrc: String @shareable
                  tracks: Int @shareable
                }
                """);

            Subgraph reporting = subgraph("reporting", """
                type Store @key(fields: "id") {
                  id: ID!
                  report(data: [ReportInput!]! @require(field: "inventory[{ code: <Book>.isbn, metric: <Book>.pages } | { code: <Movie>.imdbCode, metric: <Movie>.duration }]")): String
                }
                input ReportInput {
                  code: String!
                  metric: Int!
                }
                """);

            // Should fail: Music type is not covered by any alternative
            ValidationResult result = validate(catalog, reporting);
            assertThat(result.hasErrors()).isTrue();
            assertThat(result.errors())
                .anyMatch(e -> e.message().contains("Music") || e.message().contains("not covered"));
        }
    }
}
