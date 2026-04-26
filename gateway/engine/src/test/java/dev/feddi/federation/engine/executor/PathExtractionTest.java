package dev.feddi.federation.engine.executor;

import dev.feddi.federation.engine.parser.FieldSelectionMap.Path;
import dev.feddi.federation.engine.parser.FieldSelectionMap.PathSegment;
import dev.feddi.federation.engine.parser.FieldSelectionMap.SelectedValue;
import dev.feddi.federation.engine.parser.FieldSelectionMapParser;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive tests for FieldSelectionMap (FSM) extraction logic in Executor.
 * Tests all FSM features: paths, type conditions, object selections, list selections, and alternatives.
 */
class PathExtractionTest {

    private final Executor executor = new Executor(Map.of());

    // ==================== Simple Paths ====================

    @Nested
    class SimplePaths {

        @Test
        void singleField() {
            var selection = FieldSelectionMapParser.parseFieldSelectionMap("name");
            Path path = (Path) selection.alternatives().get(0);

            Map<String, Object> context = Map.of("name", "Alice", "age", 30);

            Object result = executor.extractValueFromPath(context, path);
            assertThat(result).isEqualTo("Alice");
        }

        @Test
        void nestedPath() {
            var selection = FieldSelectionMapParser.parseFieldSelectionMap("address.city");
            Path path = (Path) selection.alternatives().get(0);

            Map<String, Object> context = Map.of(
                "name", "Alice",
                "address", Map.of("city", "New York", "zip", "10001")
            );

            Object result = executor.extractValueFromPath(context, path);
            assertThat(result).isEqualTo("New York");
        }

        @Test
        void deeplyNestedPath() {
            var selection = FieldSelectionMapParser.parseFieldSelectionMap("user.profile.settings.theme");
            Path path = (Path) selection.alternatives().get(0);

            Map<String, Object> context = Map.of(
                "user", Map.of(
                    "profile", Map.of(
                        "settings", Map.of("theme", "dark")
                    )
                )
            );

            Object result = executor.extractValueFromPath(context, path);
            assertThat(result).isEqualTo("dark");
        }

        @Test
        void missingField_returnsNull() {
            var selection = FieldSelectionMapParser.parseFieldSelectionMap("missing");
            Path path = (Path) selection.alternatives().get(0);

            Map<String, Object> context = Map.of("name", "Alice");

            Object result = executor.extractValueFromPath(context, path);
            assertThat(result).isNull();
        }

        @Test
        void missingNestedField_returnsNull() {
            var selection = FieldSelectionMapParser.parseFieldSelectionMap("address.city");
            Path path = (Path) selection.alternatives().get(0);

            Map<String, Object> context = Map.of("name", "Alice");

            Object result = executor.extractValueFromPath(context, path);
            assertThat(result).isNull();
        }

        @Test
        void nullFieldValue_returnsNull() {
            var selection = FieldSelectionMapParser.parseFieldSelectionMap("name");
            Path path = (Path) selection.alternatives().get(0);

            Map<String, Object> context = new HashMap<>();
            context.put("name", null);

            Object result = executor.extractValueFromPath(context, path);
            assertThat(result).isNull();
        }
    }

    // ==================== Initial Type Conditions ====================

    @Nested
    class InitialTypeConditions {

        @Test
        void matchingType_extracts() {
            var selection = FieldSelectionMapParser.parseFieldSelectionMap("<Movie>.code");
            Path path = (Path) selection.alternatives().get(0);

            assertThat(path.hasInitialTypeCondition()).isTrue();
            assertThat(path.initialTypeCondition()).isEqualTo("Movie");

            Map<String, Object> context = Map.of(
                "__typename", "Movie",
                "code", "tt1375666"
            );

            Object result = executor.extractValueFromPath(context, path);
            assertThat(result).isEqualTo("tt1375666");
        }

        @Test
        void nonMatchingType_returnsNull() {
            var selection = FieldSelectionMapParser.parseFieldSelectionMap("<Movie>.code");
            Path path = (Path) selection.alternatives().get(0);

            Map<String, Object> context = Map.of(
                "__typename", "TVShow",
                "code", "81189"
            );

            Object result = executor.extractValueFromPath(context, path);
            assertThat(result).isNull();
        }

        @Test
        void missingTypename_returnsNull() {
            var selection = FieldSelectionMapParser.parseFieldSelectionMap("<Movie>.code");
            Path path = (Path) selection.alternatives().get(0);

            Map<String, Object> context = Map.of("code", "tt1375666");

            Object result = executor.extractValueFromPath(context, path);
            assertThat(result).isNull();
        }

        @Test
        void withNestedPath() {
            var selection = FieldSelectionMapParser.parseFieldSelectionMap("<Product>.details.sku");
            Path path = (Path) selection.alternatives().get(0);

            Map<String, Object> productContext = Map.of(
                "__typename", "Product",
                "details", Map.of("sku", "SKU-123")
            );

            Map<String, Object> serviceContext = Map.of(
                "__typename", "Service",
                "details", Map.of("sku", "SVC-456")
            );

            assertThat(executor.extractValueFromPath(productContext, path)).isEqualTo("SKU-123");
            assertThat(executor.extractValueFromPath(serviceContext, path)).isNull();
        }
    }

    // ==================== Infix Type Conditions ====================

    @Nested
    class InfixTypeConditions {

        @Test
        void matchingType_extracts() {
            // media<Movie>.code - get media, check it's a Movie, then get code
            Path path = new Path(List.of(
                new PathSegment("media", "Movie"),
                new PathSegment("code", null)
            ));

            Map<String, Object> context = Map.of(
                "media", Map.of(
                    "__typename", "Movie",
                    "code", "tt1375666"
                )
            );

            Object result = executor.extractValueFromPath(context, path);
            assertThat(result).isEqualTo("tt1375666");
        }

        @Test
        void nonMatchingType_returnsNull() {
            Path path = new Path(List.of(
                new PathSegment("media", "Movie"),
                new PathSegment("code", null)
            ));

            Map<String, Object> context = Map.of(
                "media", Map.of(
                    "__typename", "TVShow",
                    "code", "81189"
                )
            );

            Object result = executor.extractValueFromPath(context, path);
            assertThat(result).isNull();
        }

        @Test
        void missingTypenameOnNestedObject_returnsNull() {
            Path path = new Path(List.of(
                new PathSegment("media", "Movie"),
                new PathSegment("code", null)
            ));

            Map<String, Object> context = Map.of(
                "media", Map.of("code", "tt1375666")
            );

            Object result = executor.extractValueFromPath(context, path);
            assertThat(result).isNull();
        }

        @Test
        void multipleInfixConditions() {
            // parent<User>.child<Admin>.permission
            Path path = new Path(List.of(
                new PathSegment("parent", "User"),
                new PathSegment("child", "Admin"),
                new PathSegment("permission", null)
            ));

            Map<String, Object> validContext = Map.of(
                "parent", Map.of(
                    "__typename", "User",
                    "child", Map.of(
                        "__typename", "Admin",
                        "permission", "FULL_ACCESS"
                    )
                )
            );

            Map<String, Object> invalidChildType = Map.of(
                "parent", Map.of(
                    "__typename", "User",
                    "child", Map.of(
                        "__typename", "Guest",
                        "permission", "READ_ONLY"
                    )
                )
            );

            assertThat(executor.extractValueFromPath(validContext, path)).isEqualTo("FULL_ACCESS");
            assertThat(executor.extractValueFromPath(invalidChildType, path)).isNull();
        }
    }

    // ==================== List Traversal ====================

    @Nested
    class ListTraversal {

        @Test
        void extractFromListOfObjects() {
            var selection = FieldSelectionMapParser.parseFieldSelectionMap("items.name");
            Path path = (Path) selection.alternatives().get(0);

            Map<String, Object> context = Map.of(
                "items", List.of(
                    Map.of("name", "Item1"),
                    Map.of("name", "Item2"),
                    Map.of("name", "Item3")
                )
            );

            Object result = executor.extractValueFromPath(context, path);
            assertThat(result).isEqualTo(List.of("Item1", "Item2", "Item3"));
        }

        @Test
        void extractFromNestedLists() {
            var selection = FieldSelectionMapParser.parseFieldSelectionMap("categories.products.sku");
            Path path = (Path) selection.alternatives().get(0);

            Map<String, Object> context = Map.of(
                "categories", List.of(
                    Map.of("products", List.of(
                        Map.of("sku", "SKU-1"),
                        Map.of("sku", "SKU-2")
                    )),
                    Map.of("products", List.of(
                        Map.of("sku", "SKU-3")
                    ))
                )
            );

            Object result = executor.extractValueFromPath(context, path);
            // Lists should be flattened
            assertThat(result).isEqualTo(List.of("SKU-1", "SKU-2", "SKU-3"));
        }

        @Test
        void emptyList_returnsEmptyList() {
            var selection = FieldSelectionMapParser.parseFieldSelectionMap("items.name");
            Path path = (Path) selection.alternatives().get(0);

            Map<String, Object> context = Map.of("items", List.of());

            Object result = executor.extractValueFromPath(context, path);
            assertThat(result).isEqualTo(List.of());
        }

        @Test
        void listWithNullValues_skipsNulls() {
            var selection = FieldSelectionMapParser.parseFieldSelectionMap("items.name");
            Path path = (Path) selection.alternatives().get(0);

            Map<String, Object> item1 = new HashMap<>();
            item1.put("name", "Item1");
            Map<String, Object> item2 = new HashMap<>();
            item2.put("name", null);
            Map<String, Object> item3 = new HashMap<>();
            item3.put("name", "Item3");

            Map<String, Object> context = Map.of("items", List.of(item1, item2, item3));

            Object result = executor.extractValueFromPath(context, path);
            assertThat(result).isEqualTo(List.of("Item1", "Item3"));
        }

        @Test
        void listWithTypeCondition() {
            // items<Product>.sku - only extract sku from Product items
            Path path = new Path(List.of(
                new PathSegment("items", "Product"),
                new PathSegment("sku", null)
            ));

            Map<String, Object> context = Map.of(
                "items", List.of(
                    Map.of("__typename", "Product", "sku", "SKU-1"),
                    Map.of("__typename", "Service", "sku", "SVC-1"),
                    Map.of("__typename", "Product", "sku", "SKU-2")
                )
            );

            Object result = executor.extractValueFromPath(context, path);
            assertThat(result).isEqualTo(List.of("SKU-1", "SKU-2"));
        }
    }

    // ==================== Object Selections ====================

    @Nested
    class ObjectSelections {

        @Test
        void simpleObjectSelection() {
            // { productName: name }
            var selection = FieldSelectionMapParser.parseFieldSelectionMap("{ productName: name }");

            Map<String, Object> context = Map.of(
                "name", "Laptop",
                "price", 999
            );

            Object result = executor.extractFromSelectedValue(context, selection);
            assertThat(result).isEqualTo(Map.of("productName", "Laptop"));
        }

        @Test
        void multipleFieldsObjectSelection() {
            // { productName: name, productPrice: price }
            var selection = FieldSelectionMapParser.parseFieldSelectionMap("{ productName: name, productPrice: price }");

            Map<String, Object> context = Map.of(
                "name", "Laptop",
                "price", 999
            );

            Object result = executor.extractFromSelectedValue(context, selection);
            assertThat(result).isEqualTo(Map.of("productName", "Laptop", "productPrice", 999));
        }

        @Test
        void objectSelectionWithNestedPaths() {
            // { city: address.city, zip: address.zip }
            var selection = FieldSelectionMapParser.parseFieldSelectionMap("{ city: address.city, zip: address.zip }");

            Map<String, Object> context = Map.of(
                "address", Map.of("city", "NYC", "zip", "10001")
            );

            Object result = executor.extractFromSelectedValue(context, selection);
            assertThat(result).isEqualTo(Map.of("city", "NYC", "zip", "10001"));
        }

        @Test
        void objectSelectionWithPathPrefix() {
            // dimension.{ size weight }
            var selection = FieldSelectionMapParser.parseFieldSelectionMap("dimension.{ size weight }");

            Map<String, Object> context = Map.of(
                "dimension", Map.of("size", "large", "weight", 5.5)
            );

            Object result = executor.extractFromSelectedValue(context, selection);
            assertThat(result).isEqualTo(Map.of("size", "large", "weight", 5.5));
        }

        @Test
        void objectSelectionWithMissingField_partialResult() {
            // { a: fieldA, b: fieldB }
            var selection = FieldSelectionMapParser.parseFieldSelectionMap("{ a: fieldA, b: fieldB }");

            Map<String, Object> context = Map.of("fieldA", "valueA");

            Object result = executor.extractFromSelectedValue(context, selection);
            // Only fieldA exists, so result should only have 'a'
            assertThat(result).isEqualTo(Map.of("a", "valueA"));
        }

        @Test
        void objectSelectionWithAllFieldsMissing_returnsNull() {
            var selection = FieldSelectionMapParser.parseFieldSelectionMap("{ a: missing1, b: missing2 }");

            Map<String, Object> context = Map.of("other", "value");

            Object result = executor.extractFromSelectedValue(context, selection);
            assertThat(result).isNull();
        }
    }

    // ==================== List Selections ====================

    @Nested
    class ListSelections {

        @Test
        void simpleListSelection() {
            // items[id]
            var selection = FieldSelectionMapParser.parseFieldSelectionMap("items[id]");

            Map<String, Object> context = Map.of(
                "items", List.of(
                    Map.of("id", "1", "name", "Item1"),
                    Map.of("id", "2", "name", "Item2")
                )
            );

            Object result = executor.extractFromSelectedValue(context, selection);
            assertThat(result).isEqualTo(List.of("1", "2"));
        }

        @Test
        void listSelectionWithObjectElement() {
            // items[{ itemId: id, itemName: name }]
            var selection = FieldSelectionMapParser.parseFieldSelectionMap("items[{ itemId: id, itemName: name }]");

            Map<String, Object> context = Map.of(
                "items", List.of(
                    Map.of("id", "1", "name", "Item1"),
                    Map.of("id", "2", "name", "Item2")
                )
            );

            Object result = executor.extractFromSelectedValue(context, selection);
            assertThat(result).isEqualTo(List.of(
                Map.of("itemId", "1", "itemName", "Item1"),
                Map.of("itemId", "2", "itemName", "Item2")
            ));
        }

        @Test
        void listSelectionWithNestedPath() {
            // orders[details.total]
            var selection = FieldSelectionMapParser.parseFieldSelectionMap("orders[details.total]");

            Map<String, Object> context = Map.of(
                "orders", List.of(
                    Map.of("details", Map.of("total", 100)),
                    Map.of("details", Map.of("total", 200))
                )
            );

            Object result = executor.extractFromSelectedValue(context, selection);
            assertThat(result).isEqualTo(List.of(100, 200));
        }

        @Test
        void emptyList_returnsEmptyList() {
            var selection = FieldSelectionMapParser.parseFieldSelectionMap("items[id]");

            Map<String, Object> context = Map.of("items", List.of());

            Object result = executor.extractFromSelectedValue(context, selection);
            assertThat(result).isEqualTo(List.of());
        }

        @Test
        void nonListField_returnsNull() {
            var selection = FieldSelectionMapParser.parseFieldSelectionMap("item[id]");

            Map<String, Object> context = Map.of(
                "item", Map.of("id", "1")  // Not a list
            );

            Object result = executor.extractFromSelectedValue(context, selection);
            assertThat(result).isNull();
        }
    }

    // ==================== Alternatives ====================

    @Nested
    class Alternatives {

        @Test
        void firstAlternativeMatches() {
            // code | legacyCode
            var selection = FieldSelectionMapParser.parseFieldSelectionMap("code | legacyCode");

            Map<String, Object> context = Map.of(
                "code", "NEW-123",
                "legacyCode", "OLD-456"
            );

            Object result = executor.extractFromSelectedValue(context, selection);
            assertThat(result).isEqualTo("NEW-123");
        }

        @Test
        void secondAlternativeMatches() {
            // code | legacyCode
            var selection = FieldSelectionMapParser.parseFieldSelectionMap("code | legacyCode");

            Map<String, Object> context = Map.of("legacyCode", "OLD-456");

            Object result = executor.extractFromSelectedValue(context, selection);
            assertThat(result).isEqualTo("OLD-456");
        }

        @Test
        void noAlternativeMatches_returnsNull() {
            var selection = FieldSelectionMapParser.parseFieldSelectionMap("code | legacyCode");

            Map<String, Object> context = Map.of("other", "value");

            Object result = executor.extractFromSelectedValue(context, selection);
            assertThat(result).isNull();
        }

        @Test
        void alternativesWithTypeConditions() {
            // <Movie>.imdbCode | <TVShow>.tvdbCode
            var selection = FieldSelectionMapParser.parseFieldSelectionMap("<Movie>.imdbCode | <TVShow>.tvdbCode");

            Map<String, Object> movieContext = Map.of(
                "__typename", "Movie",
                "imdbCode", "tt1375666"
            );

            Map<String, Object> tvShowContext = Map.of(
                "__typename", "TVShow",
                "tvdbCode", "81189"
            );

            assertThat(executor.extractFromSelectedValue(movieContext, selection)).isEqualTo("tt1375666");
            assertThat(executor.extractFromSelectedValue(tvShowContext, selection)).isEqualTo("81189");
        }

        @Test
        void alternativesWithDifferentSelectionTypes() {
            // id | { compositeId: key.part1 }
            var selection = FieldSelectionMapParser.parseFieldSelectionMap("id | { compositeId: key.part1 }");

            Map<String, Object> simpleContext = Map.of("id", "123");
            Map<String, Object> compositeContext = Map.of(
                "key", Map.of("part1", "ABC")
            );

            assertThat(executor.extractFromSelectedValue(simpleContext, selection)).isEqualTo("123");
            assertThat(executor.extractFromSelectedValue(compositeContext, selection))
                .isEqualTo(Map.of("compositeId", "ABC"));
        }
    }

    // ==================== Combined Features ====================

    @Nested
    class CombinedFeatures {

        @Test
        void typeConditionWithListTraversal() {
            // <Order>.items.productId
            var selection = FieldSelectionMapParser.parseFieldSelectionMap("<Order>.items.productId");
            Path path = (Path) selection.alternatives().get(0);

            Map<String, Object> orderContext = Map.of(
                "__typename", "Order",
                "items", List.of(
                    Map.of("productId", "P1"),
                    Map.of("productId", "P2")
                )
            );

            Map<String, Object> quoteContext = Map.of(
                "__typename", "Quote",
                "items", List.of(
                    Map.of("productId", "Q1")
                )
            );

            assertThat(executor.extractValueFromPath(orderContext, path))
                .isEqualTo(List.of("P1", "P2"));
            assertThat(executor.extractValueFromPath(quoteContext, path)).isNull();
        }

        @Test
        void objectSelectionWithTypeConditionViaAlternatives() {
            // Use alternatives to achieve type-conditional object selection
            // <Product>.details.sku extracts sku only from Products
            var skuSelection = FieldSelectionMapParser.parseFieldSelectionMap("<Product>.details.sku");
            var nameSelection = FieldSelectionMapParser.parseFieldSelectionMap("<Product>.details.name");

            Map<String, Object> productContext = Map.of(
                "__typename", "Product",
                "details", Map.of("sku", "SKU-123", "name", "Laptop")
            );

            Map<String, Object> serviceContext = Map.of(
                "__typename", "Service",
                "details", Map.of("sku", "SVC-456", "name", "Support")
            );

            // Product context should extract
            assertThat(executor.extractFromSelectedValue(productContext, skuSelection)).isEqualTo("SKU-123");
            assertThat(executor.extractFromSelectedValue(productContext, nameSelection)).isEqualTo("Laptop");

            // Service context should not extract
            assertThat(executor.extractFromSelectedValue(serviceContext, skuSelection)).isNull();
            assertThat(executor.extractFromSelectedValue(serviceContext, nameSelection)).isNull();
        }

        @Test
        void listSelectionWithTypeConditionInElement() {
            // items[<Product>.sku]
            var selection = FieldSelectionMapParser.parseFieldSelectionMap("items[<Product>.sku]");

            Map<String, Object> context = Map.of(
                "items", List.of(
                    Map.of("__typename", "Product", "sku", "SKU-1"),
                    Map.of("__typename", "Service", "sku", "SVC-1"),
                    Map.of("__typename", "Product", "sku", "SKU-2")
                )
            );

            Object result = executor.extractFromSelectedValue(context, selection);
            // Only Products should be extracted
            assertThat(result).isEqualTo(List.of("SKU-1", "SKU-2"));
        }

        @Test
        void deeplyNestedWithMultipleFeatures() {
            // <Order>.customer.addresses[{ city zip }]
            var selection = FieldSelectionMapParser.parseFieldSelectionMap(
                "<Order>.customer.addresses[{ city zip }]"
            );

            Map<String, Object> orderContext = Map.of(
                "__typename", "Order",
                "customer", Map.of(
                    "addresses", List.of(
                        Map.of("city", "NYC", "zip", "10001", "street", "Main St"),
                        Map.of("city", "LA", "zip", "90001", "street", "Broadway")
                    )
                )
            );

            Object result = executor.extractFromSelectedValue(orderContext, selection);
            assertThat(result).isEqualTo(List.of(
                Map.of("city", "NYC", "zip", "10001"),
                Map.of("city", "LA", "zip", "90001")
            ));
        }
    }
}
