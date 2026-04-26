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
 * Comprehensive tests for IsInvalidFieldsRule covering all FieldSelectionMap syntax.
 *
 * The @is directive maps a @lookup argument to a field on the return type.
 * Unlike @require which requires fields from OTHER schemas, @is validates
 * that referenced fields exist in ANY schema (including the current one).
 *
 * Test categories:
 * - Simple paths: "id", "sku"
 * - Nested paths: "owner.id", "dimension.size"
 * - Type conditions: "<Movie>.imdbCode", "media<Movie>.imdbCode"
 * - Object selections: "{ width, height }", "{ w: width, h: height }"
 * - Path-prefixed objects: "dimension.{ size, weight }"
 * - List selections: "items[id]", "items[{ id, name }]"
 * - Nested lists: "matrix[[id]]"
 * - Alternatives: "isbn | upc"
 * - Alternatives with type conditions: "<Book>.isbn | <Movie>.imdbCode"
 */
class IsInvalidFieldsRuleTest {

    private static final String CODE = "IS_INVALID_FIELDS";

    private SubgraphParser parser;
    private IsInvalidFieldsRule rule;

    @BeforeEach
    void setUp() {
        parser = new SubgraphParser();
        rule = new IsInvalidFieldsRule();
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
        void valid_simpleField_matchesArgumentName() {
            // When argument name matches field name, @is is not needed but allowed
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  productById(id: ID! @is(field: "id")): Product @lookup
                }
                type Product @key(fields: "id") {
                  id: ID!
                  name: String
                }
                """);

            assertValid(validate(catalog));
        }

        @Test
        void valid_simpleField_differentArgumentName() {
            // @is maps argument "productId" to field "id"
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  productById(productId: ID! @is(field: "id")): Product @lookup
                }
                type Product @key(fields: "id") {
                  id: ID!
                  name: String
                }
                """);

            assertValid(validate(catalog));
        }

        @Test
        void valid_simpleField_alternativeKey() {
            // @is maps argument "productSku" to field "sku"
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  productBySku(productSku: String! @is(field: "sku")): Product @lookup
                }
                type Product @key(fields: "id") {
                  id: ID!
                  sku: String!
                  name: String
                }
                """);

            assertValid(validate(catalog));
        }

        @Test
        void invalid_simpleField_doesNotExist() {
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  productById(productId: ID! @is(field: "unknownField")): Product @lookup
                }
                type Product @key(fields: "id") {
                  id: ID!
                  name: String
                }
                """);

            assertInvalidWithMessage(validate(catalog), "does not exist");
        }

        @Test
        void valid_simpleField_existsInOtherSchema() {
            // @is can reference fields from other schemas (merged into return type)
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  products: [Product]
                }
                type Product @key(fields: "id") {
                  id: ID!
                  name: String
                }
                """);

            Subgraph inventory = subgraph("inventory", """
                type Query {
                  productBySku(productSku: String! @is(field: "sku")): Product @lookup
                }
                type Product @key(fields: "id") {
                  id: ID!
                  sku: String!
                }
                """);

            assertValid(validate(catalog, inventory));
        }

        @Test
        void valid_nestedPath() {
            Subgraph accounts = subgraph("accounts", """
                type Query {
                  accountByOwnerId(ownerId: ID! @is(field: "owner.id")): Account @lookup
                }
                type Account @key(fields: "id") {
                  id: ID!
                  owner: User
                }
                type User {
                  id: ID!
                  name: String
                }
                """);

            assertValid(validate(accounts));
        }

        @Test
        void invalid_nestedPath_intermediateFieldMissing() {
            Subgraph accounts = subgraph("accounts", """
                type Query {
                  accountByOwnerId(ownerId: ID! @is(field: "owner.id")): Account @lookup
                }
                type Account @key(fields: "id") {
                  id: ID!
                }
                """);

            assertInvalidWithMessage(validate(accounts), "owner");
        }

        @Test
        void invalid_nestedPath_leafFieldMissing() {
            Subgraph accounts = subgraph("accounts", """
                type Query {
                  accountByOwnerId(ownerId: ID! @is(field: "owner.email")): Account @lookup
                }
                type Account @key(fields: "id") {
                  id: ID!
                  owner: User
                }
                type User {
                  id: ID!
                  name: String
                }
                """);

            assertInvalidWithMessage(validate(accounts), "email");
        }

        @Test
        void valid_nestedPath_threeLevel() {
            Subgraph org = subgraph("org", """
                type Query {
                  companyByHqCity(city: String! @is(field: "headquarters.address.city")): Company @lookup
                }
                type Company @key(fields: "id") {
                  id: ID!
                  headquarters: Office
                }
                type Office {
                  address: Address
                }
                type Address {
                  city: String
                  country: String
                }
                """);

            assertValid(validate(org));
        }
    }

    // ========================================================================
    // Type Conditions
    // ========================================================================

    @Nested
    class TypeConditions {

        @Test
        void valid_initialTypeCondition() {
            // <Movie>.imdbCode - initial type condition narrows interface to concrete type
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  mediaByCode(code: String! @is(field: "<Movie>.imdbCode")): Media @lookup
                }
                interface Media @key(fields: "id") {
                  id: ID!
                }
                type Movie implements Media @key(fields: "id") {
                  id: ID!
                  imdbCode: String
                }
                type TVShow implements Media @key(fields: "id") {
                  id: ID!
                  tvdbCode: String
                }
                """);

            assertValid(validate(catalog));
        }

        @Test
        void invalid_initialTypeCondition_fieldMissing() {
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  mediaByCode(code: String! @is(field: "<Movie>.unknownField")): Media @lookup
                }
                interface Media @key(fields: "id") {
                  id: ID!
                }
                type Movie implements Media @key(fields: "id") {
                  id: ID!
                  imdbCode: String
                }
                """);

            assertInvalidWithMessage(validate(catalog), "unknownField");
        }

        @Test
        void invalid_initialTypeCondition_typeDoesNotExist() {
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  mediaByCode(code: String! @is(field: "<NonExistent>.imdbCode")): Media @lookup
                }
                interface Media @key(fields: "id") {
                  id: ID!
                }
                type Movie implements Media @key(fields: "id") {
                  id: ID!
                  imdbCode: String
                }
                """);

            assertInvalidWithMessage(validate(catalog), "NonExistent");
        }

        @Test
        void valid_infixTypeCondition() {
            // result<Movie>.imdbCode - infix type condition narrows field return type
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  contentByCode(code: String! @is(field: "result<Movie>.imdbCode")): Content @lookup
                }
                type Content @key(fields: "id") {
                  id: ID!
                  result: Media
                }
                interface Media {
                  id: ID!
                }
                type Movie implements Media {
                  id: ID!
                  imdbCode: String
                }
                type TVShow implements Media {
                  id: ID!
                  tvdbCode: String
                }
                """);

            assertValid(validate(catalog));
        }

        @Test
        void invalid_infixTypeCondition_fieldDoesNotExist() {
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  contentByCode(code: String! @is(field: "result<Movie>.unknownField")): Content @lookup
                }
                type Content @key(fields: "id") {
                  id: ID!
                  result: Media
                }
                interface Media {
                  id: ID!
                }
                type Movie implements Media {
                  id: ID!
                  imdbCode: String
                }
                """);

            assertInvalidWithMessage(validate(catalog), "unknownField");
        }

        @Test
        void invalid_infixTypeCondition_typeDoesNotExist() {
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  contentByCode(code: String! @is(field: "result<NonExistent>.imdbCode")): Content @lookup
                }
                type Content @key(fields: "id") {
                  id: ID!
                  result: Media
                }
                interface Media {
                  id: ID!
                }
                type Movie implements Media {
                  id: ID!
                  imdbCode: String
                }
                """);

            assertInvalidWithMessage(validate(catalog), "NonExistent");
        }

        @Test
        void valid_chainedInfixTypeConditions() {
            // content<Article>.author<Person>.name - multiple chained type conditions
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  itemByAuthor(name: String! @is(field: "content<Article>.author<Person>.name")): Item @lookup
                }
                type Item @key(fields: "id") {
                  id: ID!
                  content: Content
                }
                interface Content {
                  id: ID!
                }
                type Article implements Content {
                  id: ID!
                  author: Author
                }
                interface Author {
                  id: ID!
                }
                type Person implements Author {
                  id: ID!
                  name: String
                }
                type Organization implements Author {
                  id: ID!
                  orgName: String
                }
                """);

            assertValid(validate(catalog));
        }

        @Test
        void invalid_chainedInfixTypeConditions_secondTypeDoesNotExist() {
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  itemByAuthor(name: String! @is(field: "content<Article>.author<NonExistent>.name")): Item @lookup
                }
                type Item @key(fields: "id") {
                  id: ID!
                  content: Content
                }
                interface Content {
                  id: ID!
                }
                type Article implements Content {
                  id: ID!
                  author: Author
                }
                interface Author {
                  id: ID!
                }
                type Person implements Author {
                  id: ID!
                  name: String
                }
                """);

            assertInvalidWithMessage(validate(catalog), "NonExistent");
        }

        @Test
        void valid_initialAndInfixTypeConditions() {
            // <Item>.content<Article>.title - both initial and infix
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  searchByTitle(title: String! @is(field: "<Item>.content<Article>.title")): SearchResult @lookup
                }
                interface SearchResult @key(fields: "id") {
                  id: ID!
                }
                type Item implements SearchResult @key(fields: "id") {
                  id: ID!
                  content: Content
                }
                interface Content {
                  id: ID!
                }
                type Article implements Content {
                  id: ID!
                  title: String
                }
                """);

            assertValid(validate(catalog));
        }
    }

    // ========================================================================
    // Object Selections
    // ========================================================================

    @Nested
    class ObjectSelections {

        @Test
        void valid_objectSelection_simpleFields() {
            // { trackingNumber, carrier } - multiple fields as composite key
            Subgraph shipping = subgraph("shipping", """
                type Query {
                  shipmentByTracking(data: ShipmentInput! @is(field: "{ trackingNumber, carrier }")): Shipment @lookup
                }
                input ShipmentInput {
                  trackingNumber: String!
                  carrier: String!
                }
                type Shipment @key(fields: "trackingNumber carrier") {
                  trackingNumber: String!
                  carrier: String!
                  status: String
                }
                """);

            assertValid(validate(shipping));
        }

        @Test
        void valid_objectSelection_withAliases() {
            // { tracking: trackingNumber, shippingCarrier: carrier }
            Subgraph shipping = subgraph("shipping", """
                type Query {
                  shipmentByTracking(data: ShipmentInput! @is(field: "{ tracking: trackingNumber, shippingCarrier: carrier }")): Shipment @lookup
                }
                input ShipmentInput {
                  tracking: String!
                  shippingCarrier: String!
                }
                type Shipment @key(fields: "trackingNumber carrier") {
                  trackingNumber: String!
                  carrier: String!
                  status: String
                }
                """);

            assertValid(validate(shipping));
        }

        @Test
        void invalid_objectSelection_fieldMissing() {
            Subgraph shipping = subgraph("shipping", """
                type Query {
                  shipmentByTracking(data: ShipmentInput! @is(field: "{ trackingNumber, unknownField }")): Shipment @lookup
                }
                input ShipmentInput {
                  trackingNumber: String!
                  unknownField: String!
                }
                type Shipment @key(fields: "trackingNumber") {
                  trackingNumber: String!
                  carrier: String!
                }
                """);

            assertInvalidWithMessage(validate(shipping), "unknownField");
        }

        @Test
        void valid_objectSelection_nestedPaths() {
            // { city: address.city, country: address.country }
            Subgraph org = subgraph("org", """
                type Query {
                  companyByLocation(loc: LocationInput! @is(field: "{ city: address.city, country: address.country }")): Company @lookup
                }
                input LocationInput {
                  city: String!
                  country: String!
                }
                type Company @key(fields: "id") {
                  id: ID!
                  address: Address
                }
                type Address {
                  city: String
                  country: String
                  zip: String
                }
                """);

            assertValid(validate(org));
        }

        @Test
        void invalid_objectSelection_nestedPathMissing() {
            Subgraph org = subgraph("org", """
                type Query {
                  companyByLocation(loc: LocationInput! @is(field: "{ city: address.unknownField }")): Company @lookup
                }
                input LocationInput {
                  city: String!
                }
                type Company @key(fields: "id") {
                  id: ID!
                  address: Address
                }
                type Address {
                  city: String
                }
                """);

            assertInvalidWithMessage(validate(org), "unknownField");
        }

        @Test
        void valid_pathPrefixedObjectSelection() {
            // dimension.{ width, height } - path prefix with object selection
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  productBySize(size: SizeInput! @is(field: "dimension.{ width, height }")): Product @lookup
                }
                input SizeInput {
                  width: Int!
                  height: Int!
                }
                type Product @key(fields: "id") {
                  id: ID!
                  dimension: Dimension
                }
                type Dimension {
                  width: Int
                  height: Int
                  depth: Int
                }
                """);

            assertValid(validate(catalog));
        }

        @Test
        void invalid_pathPrefixedObjectSelection_prefixMissing() {
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  productBySize(size: SizeInput! @is(field: "unknownPrefix.{ width, height }")): Product @lookup
                }
                input SizeInput {
                  width: Int!
                  height: Int!
                }
                type Product @key(fields: "id") {
                  id: ID!
                }
                """);

            assertInvalidWithMessage(validate(catalog), "unknownPrefix");
        }

        @Test
        void invalid_pathPrefixedObjectSelection_fieldMissing() {
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  productBySize(size: SizeInput! @is(field: "dimension.{ width, unknownField }")): Product @lookup
                }
                input SizeInput {
                  width: Int!
                  unknownField: Int!
                }
                type Product @key(fields: "id") {
                  id: ID!
                  dimension: Dimension
                }
                type Dimension {
                  width: Int
                  height: Int
                }
                """);

            assertInvalidWithMessage(validate(catalog), "unknownField");
        }
    }

    // ========================================================================
    // List Selections
    // ========================================================================

    @Nested
    class ListSelections {

        @Test
        void valid_listSelection_simpleField() {
            // items[id] - extract id from each list element
            Subgraph orders = subgraph("orders", """
                type Query {
                  orderByItemIds(itemIds: [ID!]! @is(field: "items[id]")): Order @lookup
                }
                type Order @key(fields: "id") {
                  id: ID!
                  items: [OrderItem!]!
                }
                type OrderItem {
                  id: ID!
                  name: String
                  quantity: Int
                }
                """);

            assertValid(validate(orders));
        }

        @Test
        void invalid_listSelection_fieldMissing() {
            Subgraph orders = subgraph("orders", """
                type Query {
                  orderByItemIds(itemIds: [ID!]! @is(field: "items[unknownField]")): Order @lookup
                }
                type Order @key(fields: "id") {
                  id: ID!
                  items: [OrderItem!]!
                }
                type OrderItem {
                  id: ID!
                  name: String
                }
                """);

            assertInvalidWithMessage(validate(orders), "unknownField");
        }

        @Test
        void valid_listSelection_nestedPath() {
            // items[product.id] - nested path within list element
            Subgraph orders = subgraph("orders", """
                type Query {
                  orderByProductIds(productIds: [ID!]! @is(field: "items[product.id]")): Order @lookup
                }
                type Order @key(fields: "id") {
                  id: ID!
                  items: [OrderItem!]!
                }
                type OrderItem {
                  id: ID!
                  product: Product
                }
                type Product {
                  id: ID!
                  name: String
                }
                """);

            assertValid(validate(orders));
        }

        @Test
        void valid_listSelection_objectElement() {
            // items[{ id, name }] - object selection within list
            Subgraph orders = subgraph("orders", """
                type Query {
                  orderByItemData(items: [ItemInput!]! @is(field: "items[{ id, name }]")): Order @lookup
                }
                input ItemInput {
                  id: ID!
                  name: String!
                }
                type Order @key(fields: "id") {
                  id: ID!
                  items: [OrderItem!]!
                }
                type OrderItem {
                  id: ID!
                  name: String
                  quantity: Int
                }
                """);

            assertValid(validate(orders));
        }

        @Test
        void invalid_listSelection_objectElement_fieldMissing() {
            Subgraph orders = subgraph("orders", """
                type Query {
                  orderByItemData(items: [ItemInput!]! @is(field: "items[{ id, unknownField }]")): Order @lookup
                }
                input ItemInput {
                  id: ID!
                  unknownField: String!
                }
                type Order @key(fields: "id") {
                  id: ID!
                  items: [OrderItem!]!
                }
                type OrderItem {
                  id: ID!
                  name: String
                }
                """);

            assertInvalidWithMessage(validate(orders), "unknownField");
        }

        @Test
        void valid_listSelection_withPathPrefix() {
            // order.items[id] - path prefix before list
            Subgraph invoices = subgraph("invoices", """
                type Query {
                  invoiceByItemIds(itemIds: [ID!]! @is(field: "order.items[id]")): Invoice @lookup
                }
                type Invoice @key(fields: "id") {
                  id: ID!
                  order: Order
                }
                type Order {
                  id: ID!
                  items: [OrderItem!]!
                }
                type OrderItem {
                  id: ID!
                  name: String
                }
                """);

            assertValid(validate(invoices));
        }

        @Test
        void invalid_listSelection_pathPrefixMissing() {
            Subgraph invoices = subgraph("invoices", """
                type Query {
                  invoiceByItemIds(itemIds: [ID!]! @is(field: "unknownPath.items[id]")): Invoice @lookup
                }
                type Invoice @key(fields: "id") {
                  id: ID!
                }
                """);

            assertInvalidWithMessage(validate(invoices), "unknownPath");
        }
    }

    // ========================================================================
    // Nested Lists
    // ========================================================================

    @Nested
    class NestedLists {

        @Test
        void valid_nestedList_twoLevels() {
            // matrix[[id]] - extract ids from nested list
            Subgraph grids = subgraph("grids", """
                type Query {
                  gridByIds(ids: [[ID!]!]! @is(field: "matrix[[id]]")): Grid @lookup
                }
                type Grid @key(fields: "id") {
                  id: ID!
                  matrix: [[Cell!]!]!
                }
                type Cell {
                  id: ID!
                  value: Int
                }
                """);

            assertValid(validate(grids));
        }

        @Test
        void invalid_nestedList_fieldMissing() {
            Subgraph grids = subgraph("grids", """
                type Query {
                  gridByIds(ids: [[ID!]!]! @is(field: "matrix[[unknownField]]")): Grid @lookup
                }
                type Grid @key(fields: "id") {
                  id: ID!
                  matrix: [[Cell!]!]!
                }
                type Cell {
                  id: ID!
                }
                """);

            assertInvalidWithMessage(validate(grids), "unknownField");
        }

        @Test
        void valid_nestedList_withObjectElement() {
            // rows[[{ id, value }]] - nested list with object extraction
            Subgraph spreadsheets = subgraph("spreadsheets", """
                type Query {
                  sheetByCells(cells: [[CellInput!]!]! @is(field: "rows[[{ id, value }]]")): Sheet @lookup
                }
                input CellInput {
                  id: ID!
                  value: String!
                }
                type Sheet @key(fields: "id") {
                  id: ID!
                  rows: [[Cell!]!]!
                }
                type Cell {
                  id: ID!
                  value: String
                  formula: String
                }
                """);

            assertValid(validate(spreadsheets));
        }

        @Test
        void valid_nestedList_threeLevels() {
            // cube[[[id]]] - three-level nesting
            Subgraph cubes = subgraph("cubes", """
                type Query {
                  cubeByIds(ids: [[[ID!]!]!]! @is(field: "cube[[[id]]]")): Cube3D @lookup
                }
                type Cube3D @key(fields: "id") {
                  id: ID!
                  cube: [[[Voxel!]!]!]!
                }
                type Voxel {
                  id: ID!
                  color: String
                }
                """);

            assertValid(validate(cubes));
        }
    }

    // ========================================================================
    // Alternatives
    // ========================================================================

    @Nested
    class Alternatives {

        @Test
        void valid_alternatives_simpleFields() {
            // isbn | upc - either field can provide the lookup value
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  productByCode(code: String! @is(field: "isbn | upc")): Product @lookup
                }
                type Product @key(fields: "id") {
                  id: ID!
                  isbn: String
                  upc: String
                  name: String
                }
                """);

            assertValid(validate(catalog));
        }

        @Test
        void invalid_alternatives_firstFieldMissing() {
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  productByCode(code: String! @is(field: "unknownField | upc")): Product @lookup
                }
                type Product @key(fields: "id") {
                  id: ID!
                  upc: String
                }
                """);

            assertInvalidWithMessage(validate(catalog), "unknownField");
        }

        @Test
        void invalid_alternatives_secondFieldMissing() {
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  productByCode(code: String! @is(field: "isbn | unknownField")): Product @lookup
                }
                type Product @key(fields: "id") {
                  id: ID!
                  isbn: String
                }
                """);

            assertInvalidWithMessage(validate(catalog), "unknownField");
        }

        @Test
        void valid_alternatives_threeOptions() {
            // isbn | upc | asin - three alternatives
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  productByCode(code: String! @is(field: "isbn | upc | asin")): Product @lookup
                }
                type Product @key(fields: "id") {
                  id: ID!
                  isbn: String
                  upc: String
                  asin: String
                }
                """);

            assertValid(validate(catalog));
        }

        @Test
        void valid_alternatives_withNestedPaths() {
            // primary.code | secondary.code
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  productByCode(code: String! @is(field: "primary.code | secondary.code")): Product @lookup
                }
                type Product @key(fields: "id") {
                  id: ID!
                  primary: Identifier
                  secondary: Identifier
                }
                type Identifier {
                  code: String
                  system: String
                }
                """);

            assertValid(validate(catalog));
        }
    }

    // ========================================================================
    // Alternatives with Type Conditions
    // ========================================================================

    @Nested
    class AlternativesWithTypeConditions {

        @Test
        void valid_alternativesWithInitialTypeConditions() {
            // <Movie>.imdbCode | <TVShow>.tvdbCode
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  mediaByCode(code: String! @is(field: "<Movie>.imdbCode | <TVShow>.tvdbCode")): Media @lookup
                }
                interface Media @key(fields: "id") {
                  id: ID!
                }
                type Movie implements Media @key(fields: "id") {
                  id: ID!
                  imdbCode: String
                }
                type TVShow implements Media @key(fields: "id") {
                  id: ID!
                  tvdbCode: String
                }
                """);

            assertValid(validate(catalog));
        }

        @Test
        void invalid_alternativesWithTypeConditions_firstFieldMissing() {
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  mediaByCode(code: String! @is(field: "<Movie>.unknownField | <TVShow>.tvdbCode")): Media @lookup
                }
                interface Media @key(fields: "id") {
                  id: ID!
                }
                type Movie implements Media @key(fields: "id") {
                  id: ID!
                  imdbCode: String
                }
                type TVShow implements Media @key(fields: "id") {
                  id: ID!
                  tvdbCode: String
                }
                """);

            assertInvalidWithMessage(validate(catalog), "unknownField");
        }

        @Test
        void invalid_alternativesWithTypeConditions_typeDoesNotExist() {
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  mediaByCode(code: String! @is(field: "<Movie>.imdbCode | <NonExistent>.code")): Media @lookup
                }
                interface Media @key(fields: "id") {
                  id: ID!
                }
                type Movie implements Media @key(fields: "id") {
                  id: ID!
                  imdbCode: String
                }
                """);

            assertInvalidWithMessage(validate(catalog), "NonExistent");
        }

        @Test
        void valid_alternativesWithInfixTypeConditions() {
            // result<Movie>.imdbCode | result<TVShow>.tvdbCode
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  contentByCode(code: String! @is(field: "result<Movie>.imdbCode | result<TVShow>.tvdbCode")): Content @lookup
                }
                type Content @key(fields: "id") {
                  id: ID!
                  result: Media
                }
                interface Media {
                  id: ID!
                }
                type Movie implements Media {
                  id: ID!
                  imdbCode: String
                }
                type TVShow implements Media {
                  id: ID!
                  tvdbCode: String
                }
                """);

            assertValid(validate(catalog));
        }

        @Test
        void valid_alternativesMixedTypeConditions() {
            // <Book>.isbn | movie.imdbCode - mix of initial type condition and plain path
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  itemByCode(code: String! @is(field: "<Book>.isbn | movie.imdbCode")): Item @lookup
                }
                interface Item @key(fields: "id") {
                  id: ID!
                  movie: Movie
                }
                type Book implements Item @key(fields: "id") {
                  id: ID!
                  isbn: String
                  movie: Movie
                }
                type Article implements Item @key(fields: "id") {
                  id: ID!
                  movie: Movie
                }
                type Movie {
                  id: ID!
                  imdbCode: String
                }
                """);

            assertValid(validate(catalog));
        }
    }

    // ========================================================================
    // List with Type Conditions
    // ========================================================================

    @Nested
    class ListWithTypeConditions {

        @Test
        void valid_listWithTypeConditionInElement() {
            // items[<Book>.isbn | <Movie>.imdbCode]
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  collectionByCodes(codes: [String!]! @is(field: "items[<Book>.isbn | <Movie>.imdbCode]")): Collection @lookup
                }
                type Collection @key(fields: "id") {
                  id: ID!
                  items: [Media!]!
                }
                interface Media {
                  id: ID!
                }
                type Book implements Media {
                  id: ID!
                  isbn: String
                }
                type Movie implements Media {
                  id: ID!
                  imdbCode: String
                }
                """);

            assertValid(validate(catalog));
        }

        @Test
        void invalid_listWithTypeCondition_fieldMissing() {
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  collectionByCodes(codes: [String!]! @is(field: "items[<Book>.unknownField]")): Collection @lookup
                }
                type Collection @key(fields: "id") {
                  id: ID!
                  items: [Media!]!
                }
                interface Media {
                  id: ID!
                }
                type Book implements Media {
                  id: ID!
                  isbn: String
                }
                """);

            assertInvalidWithMessage(validate(catalog), "unknownField");
        }
    }

    // ========================================================================
    // Complex Combinations
    // ========================================================================

    @Nested
    class ComplexCombinations {

        @Test
        void valid_nestedListWithObjectAndTypeCondition() {
            // rows[[{ code: <Book>.isbn | <Movie>.imdbCode }]]
            Subgraph library = subgraph("library", """
                type Query {
                  shelfByCodes(data: [[CodeInput!]!]! @is(field: "rows[[{ code: <Book>.isbn | <Movie>.imdbCode }]]")): Shelf @lookup
                }
                input CodeInput {
                  code: String!
                }
                type Shelf @key(fields: "id") {
                  id: ID!
                  rows: [[Media!]!]!
                }
                interface Media {
                  id: ID!
                }
                type Book implements Media {
                  id: ID!
                  isbn: String
                }
                type Movie implements Media {
                  id: ID!
                  imdbCode: String
                }
                """);

            assertValid(validate(library));
        }

        @Test
        void valid_pathPrefixedListWithObject() {
            // warehouse.shelves[{ row, column }]
            Subgraph inventory = subgraph("inventory", """
                type Query {
                  productByLocation(locations: [LocationInput!]! @is(field: "warehouse.shelves[{ row, column }]")): Product @lookup
                }
                input LocationInput {
                  row: Int!
                  column: Int!
                }
                type Product @key(fields: "id") {
                  id: ID!
                  warehouse: Warehouse
                }
                type Warehouse {
                  id: ID!
                  shelves: [ShelfLocation!]!
                }
                type ShelfLocation {
                  row: Int
                  column: Int
                  level: Int
                }
                """);

            assertValid(validate(inventory));
        }

        @Test
        void valid_objectWithListAndTypeCondition() {
            // { ids: items[<Book>.isbn], count: total }
            Subgraph library = subgraph("library", """
                type Query {
                  collectionByData(data: CollectionInput! @is(field: "{ ids: items[<Book>.isbn], count: total }")): Collection @lookup
                }
                input CollectionInput {
                  ids: [String!]!
                  count: Int!
                }
                type Collection @key(fields: "id") {
                  id: ID!
                  items: [Media!]!
                  total: Int
                }
                interface Media {
                  id: ID!
                }
                type Book implements Media {
                  id: ID!
                  isbn: String
                }
                """);

            assertValid(validate(library));
        }

        @Test
        void invalid_complexCombination_deepFieldMissing() {
            // warehouse.shelves[{ row, unknownField }]
            Subgraph inventory = subgraph("inventory", """
                type Query {
                  productByLocation(locations: [LocationInput!]! @is(field: "warehouse.shelves[{ row, unknownField }]")): Product @lookup
                }
                input LocationInput {
                  row: Int!
                  unknownField: Int!
                }
                type Product @key(fields: "id") {
                  id: ID!
                  warehouse: Warehouse
                }
                type Warehouse {
                  id: ID!
                  shelves: [ShelfLocation!]!
                }
                type ShelfLocation {
                  row: Int
                  column: Int
                }
                """);

            assertInvalidWithMessage(validate(inventory), "unknownField");
        }
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Nested
    class EdgeCases {

        @Test
        void valid_fieldFromOtherSchema() {
            // @is can reference fields contributed by other schemas
            Subgraph products = subgraph("products", """
                type Query {
                  products: [Product]
                }
                type Product @key(fields: "id") {
                  id: ID!
                  name: String
                }
                """);

            Subgraph inventory = subgraph("inventory", """
                type Product @key(fields: "id") {
                  id: ID!
                  sku: String
                }
                """);

            Subgraph search = subgraph("search", """
                type Query {
                  productBySku(sku: String! @is(field: "sku")): Product @lookup
                }
                type Product @key(fields: "id") {
                  id: ID!
                }
                """);

            assertValid(validate(products, inventory, search));
        }

        @Test
        void valid_interfaceFieldLookup() {
            // @is on interface return type
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  mediaById(mediaId: ID! @is(field: "id")): Media @lookup
                }
                interface Media @key(fields: "id") {
                  id: ID!
                  title: String
                }
                type Movie implements Media @key(fields: "id") {
                  id: ID!
                  title: String
                  director: String
                }
                type TVShow implements Media @key(fields: "id") {
                  id: ID!
                  title: String
                  seasons: Int
                }
                """);

            assertValid(validate(catalog));
        }

        @Test
        void valid_multipleIsDirectivesOnSameLookup() {
            // Multiple @is directives on different arguments
            Subgraph shipping = subgraph("shipping", """
                type Query {
                  shipmentByTracking(
                    tracking: String! @is(field: "trackingNumber")
                    shippingCarrier: String! @is(field: "carrier")
                  ): Shipment @lookup
                }
                type Shipment @key(fields: "trackingNumber carrier") {
                  trackingNumber: String!
                  carrier: String!
                  status: String
                }
                """);

            assertValid(validate(shipping));
        }

        @Test
        void invalid_multipleIsDirectives_oneFieldMissing() {
            Subgraph shipping = subgraph("shipping", """
                type Query {
                  shipmentByTracking(
                    tracking: String! @is(field: "trackingNumber")
                    shippingCarrier: String! @is(field: "unknownField")
                  ): Shipment @lookup
                }
                type Shipment @key(fields: "trackingNumber") {
                  trackingNumber: String!
                  status: String
                }
                """);

            assertInvalidWithMessage(validate(shipping), "unknownField");
        }

        @Test
        void valid_emptyFieldString_noValidation() {
            // Empty field string - syntax validation is done by another rule
            // IsInvalidFieldsRule should not crash on empty string
            Subgraph catalog = subgraph("catalog", """
                type Query {
                  productById(id: ID! @is(field: "")): Product @lookup
                }
                type Product @key(fields: "id") {
                  id: ID!
                }
                """);

            // Should not throw, but may be invalid from syntax rule
            validate(catalog);
        }

        @Test
        void valid_unionReturnType() {
            // @is on @lookup with union return type
            Subgraph search = subgraph("search", """
                type Query {
                  searchById(searchId: ID! @is(field: "id")): SearchResult @lookup
                }
                union SearchResult = Product | Category

                type Product @key(fields: "id") {
                  id: ID!
                  name: String
                }
                type Category @key(fields: "id") {
                  id: ID!
                  title: String
                }
                """);

            assertValid(validate(search));
        }

        @Test
        void valid_typeConditionOnUnion() {
            // <Product>.sku | <Category>.slug
            Subgraph search = subgraph("search", """
                type Query {
                  searchByCode(code: String! @is(field: "<Product>.sku | <Category>.slug")): SearchResult @lookup
                }
                union SearchResult = Product | Category

                type Product @key(fields: "id") {
                  id: ID!
                  sku: String
                }
                type Category @key(fields: "id") {
                  id: ID!
                  slug: String
                }
                """);

            assertValid(validate(search));
        }
    }
}
