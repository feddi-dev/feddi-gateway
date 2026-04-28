package dev.feddi.federation.engine.graph;

import dev.feddi.federation.engine.parser.FieldSelectionMap.Path;
import dev.feddi.federation.engine.parser.FieldSelectionMap.PathSegment;
import dev.feddi.federation.engine.parser.FieldSelectionMapParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequirementTest {

    /**
     * Test helper to convert Path objects to strings for assertions.
     */
    private static List<String> pathsToStrings(List<Path> paths) {
        return paths.stream().map(RequirementTest::pathToString).toList();
    }

    private static String pathToString(Path path) {
        StringBuilder sb = new StringBuilder();
        if (path.hasInitialTypeCondition()) {
            sb.append("<").append(path.initialTypeCondition()).append(">.");
        }
        for (int i = 0; i < path.segments().size(); i++) {
            PathSegment segment = path.segments().get(i);
            if (i > 0) {
                sb.append(".");
            }
            if (segment.hasTypeCondition()) {
                sb.append("<").append(segment.typeCondition()).append(">.");
            }
            sb.append(segment.fieldName());
        }
        return sb.toString();
    }

    @Test
    void extractPaths_simplePath() {
        // @require(field: "weight")
        var selection = FieldSelectionMapParser.parseFieldSelectionMap("weight");
        var requirement = Requirement.of("w", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        assertThat(paths).containsExactly("weight");
    }

    @Test
    void extractPaths_nestedPath() {
        // @require(field: "dimension.size")
        var selection = FieldSelectionMapParser.parseFieldSelectionMap("dimension.size");
        var requirement = Requirement.of("dim", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        assertThat(paths).containsExactly("dimension.size");
    }

    @Test
    void extractPaths_objectSelection() {
        // @require(field: "{ productSize: dimension.size, productWeight: dimension.weight }")
        var selection = FieldSelectionMapParser.parseFieldSelectionMap(
            "{ productSize: dimension.size, productWeight: dimension.weight }");
        var requirement = Requirement.of("dim", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        assertThat(paths).containsExactlyInAnyOrder("dimension.size", "dimension.weight");
    }

    @Test
    void extractPaths_listSelection_simple() {
        // @require(field: "items[productId]")
        var selection = FieldSelectionMapParser.parseFieldSelectionMap("items[productId]");
        var requirement = Requirement.of("productIds", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        assertThat(paths).containsExactly("items.productId");
    }

    @Test
    void extractPaths_listSelection_withObjectElement() {
        // @require(field: "items[{ pid: productId, qty: quantity }]")
        var selection = FieldSelectionMapParser.parseFieldSelectionMap(
            "items[{ pid: productId, qty: quantity }]");
        var requirement = Requirement.of("itemDetails", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        assertThat(paths).containsExactlyInAnyOrder("items.productId", "items.quantity");
    }

    @Test
    void extractPaths_pathPrefixedObjectShorthand() {
        // @require(field: "shippingAddress.{ city state zip country }")
        var selection = FieldSelectionMapParser.parseFieldSelectionMap(
            "shippingAddress.{ city state zip country }");
        var requirement = Requirement.of("destination", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        assertThat(paths).containsExactlyInAnyOrder(
            "shippingAddress.city",
            "shippingAddress.state",
            "shippingAddress.zip",
            "shippingAddress.country"
        );
    }

    @Test
    void extractPaths_pathPrefixedObjectExplicit() {
        // @require(field: "addr.{ c: city, z: zip }")
        var selection = FieldSelectionMapParser.parseFieldSelectionMap(
            "addr.{ c: city, z: zip }");
        var requirement = Requirement.of("location", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        assertThat(paths).containsExactlyInAnyOrder("addr.city", "addr.zip");
    }

    @Test
    void extractPaths_deeplyNested() {
        // @require(field: "{ items: items[{ id: productId, count: quantity }], destination: shippingAddress.{ city zip } }")
        var selection = FieldSelectionMapParser.parseFieldSelectionMap(
            "{ items: items[{ id: productId, count: quantity }], destination: shippingAddress.{ city zip } }");
        var requirement = Requirement.of("plan", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        assertThat(paths).containsExactlyInAnyOrder(
            "items.productId",
            "items.quantity",
            "shippingAddress.city",
            "shippingAddress.zip"
        );
    }

    @Test
    void extractPaths_nestedListInObject() {
        // @require(field: "order.{ lines: items[sku] }")
        var selection = FieldSelectionMapParser.parseFieldSelectionMap(
            "order.{ lines: items[sku] }");
        var requirement = Requirement.of("orderData", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        assertThat(paths).containsExactly("order.items.sku");
    }

    @Test
    void extractPaths_alternatives() {
        // @require(field: "book.title | movie.name")
        // Alternatives should extract all paths (runtime decides which to use)
        var selection = FieldSelectionMapParser.parseFieldSelectionMap("book.title | movie.name");
        var requirement = Requirement.of("title", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        assertThat(paths).containsExactlyInAnyOrder("book.title", "movie.name");
    }

    @Test
    void extractPaths_typeCondition_initial() {
        // @require(field: "<Movie>.imdbCode")
        var selection = FieldSelectionMapParser.parseFieldSelectionMap("<Movie>.imdbCode");
        var requirement = Requirement.of("code", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        assertThat(paths).containsExactly("<Movie>.imdbCode");
    }

    @Test
    void extractPaths_typeCondition_infix() {
        // @require(field: "media<Movie>.imdbCode")
        // Parser attaches type condition to the field
        var selection = FieldSelectionMapParser.parseFieldSelectionMap("media<Movie>.imdbCode");
        var requirement = Requirement.of("code", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        assertThat(paths).containsExactly("<Movie>.media.imdbCode");
    }

    @Test
    void extractPaths_typeCondition_nested() {
        // @require(field: "content<Article>.author<Person>.name")
        // Multiple type conditions in path
        var selection = FieldSelectionMapParser.parseFieldSelectionMap("content<Article>.author<Person>.name");
        var requirement = Requirement.of("authorName", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        assertThat(paths).containsExactly("<Article>.content.<Person>.author.name");
    }

    @Test
    void extractPaths_alternativesWithTypeConditions() {
        // @require(field: "<Book>.isbn | <Movie>.imdbCode")
        var selection = FieldSelectionMapParser.parseFieldSelectionMap("<Book>.isbn | <Movie>.imdbCode");
        var requirement = Requirement.of("id", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        assertThat(paths).containsExactlyInAnyOrder("<Book>.isbn", "<Movie>.imdbCode");
    }

    @Test
    void extractPaths_alternativesWithTypeConditions_pathPrefixed() {
        // @require(field: "mediaById<Book>.title | mediaById<Movie>.movieTitle")
        var selection = FieldSelectionMapParser.parseFieldSelectionMap(
            "mediaById<Book>.title | mediaById<Movie>.movieTitle");
        var requirement = Requirement.of("title", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        assertThat(paths).containsExactlyInAnyOrder("<Book>.mediaById.title", "<Movie>.mediaById.movieTitle");
    }

    @Test
    void extractPaths_nestedList() {
        // @require(field: "matrix[[id]]") - for [[ID!]!]! type
        var selection = FieldSelectionMapParser.parseFieldSelectionMap("matrix[[id]]");
        var requirement = Requirement.of("ids", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        assertThat(paths).containsExactly("matrix.id");
    }

    @Test
    void extractPaths_nestedListWithObject() {
        // @require(field: "groups[[{ id name }]]")
        var selection = FieldSelectionMapParser.parseFieldSelectionMap("groups[[{ id name }]]");
        var requirement = Requirement.of("data", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        assertThat(paths).containsExactlyInAnyOrder("groups.id", "groups.name");
    }

    @Test
    void extractPaths_listWithTypeCondition() {
        // @require(field: "items[<Product>.sku]")
        var selection = FieldSelectionMapParser.parseFieldSelectionMap("items[<Product>.sku]");
        var requirement = Requirement.of("skus", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        assertThat(paths).containsExactly("items.<Product>.sku");
    }

    @Test
    void extractPaths_objectWithTypeCondition() {
        // @require(field: "{ bookId: <Book>.id, movieId: <Movie>.id }")
        var selection = FieldSelectionMapParser.parseFieldSelectionMap(
            "{ bookId: <Book>.id, movieId: <Movie>.id }");
        var requirement = Requirement.of("ids", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        assertThat(paths).containsExactlyInAnyOrder("<Book>.id", "<Movie>.id");
    }

    @Test
    void extractPaths_alternativesWithObjects() {
        // @require(field: "{ bookId: <Book>.id } | { movieId: <Movie>.id }")
        var selection = FieldSelectionMapParser.parseFieldSelectionMap(
            "{ bookId: <Book>.id } | { movieId: <Movie>.id }");
        var requirement = Requirement.of("id", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        assertThat(paths).containsExactlyInAnyOrder("<Book>.id", "<Movie>.id");
    }

    @Test
    void extractPaths_complexNestedWithAlternatives() {
        // @require(field: "orders[customer.addresses[{ street city } | location.coords]]")
        var selection = FieldSelectionMapParser.parseFieldSelectionMap(
            "orders[customer.addresses[{ street city } | location.coords]]");
        var requirement = Requirement.of("destinations", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        assertThat(paths).containsExactlyInAnyOrder(
            "orders.customer.addresses.street",
            "orders.customer.addresses.city",
            "orders.customer.addresses.location.coords"
        );
    }

    @Test
    void extractPaths_listWithAlternativesAndTypeConditions() {
        // @require(field: "data[{ typeA: item<A>.value } | { typeB: item<B>.value }]")
        var selection = FieldSelectionMapParser.parseFieldSelectionMap(
            "data[{ typeA: item<A>.value } | { typeB: item<B>.value }]");
        var requirement = Requirement.of("values", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        assertThat(paths).containsExactlyInAnyOrder("data.<A>.item.value", "data.<B>.item.value");
    }

    @Test
    void extractPaths_objectShorthand() {
        // @require(field: "{ id name }")
        var selection = FieldSelectionMapParser.parseFieldSelectionMap("{ id name }");
        var requirement = Requirement.of("info", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        assertThat(paths).containsExactlyInAnyOrder("id", "name");
    }

    @Test
    void extractPaths_listWithNestedPath() {
        // @require(field: "items[part.id]")
        var selection = FieldSelectionMapParser.parseFieldSelectionMap("items[part.id]");
        var requirement = Requirement.of("partIds", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        assertThat(paths).containsExactly("items.part.id");
    }

    @Test
    void extractPaths_nestedAlternativesInsideObject() {
        // @require(field: "{ nested: { bookId: <Book>.id } | { movieId: <Movie>.id } }")
        // Alternatives nested within an object field
        var selection = FieldSelectionMapParser.parseFieldSelectionMap(
            "{ nested: { bookId: <Book>.id } | { movieId: <Movie>.id } }");
        var requirement = Requirement.of("input", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        assertThat(paths).containsExactlyInAnyOrder("<Book>.id", "<Movie>.id");
    }

    @Test
    void extractPaths_packageWithNestedDimension() {
        // @require(field: "{ weight, dimension: dimension.{ width, height } }")
        var selection = FieldSelectionMapParser.parseFieldSelectionMap(
            "{ weight, dimension: dimension.{ width, height } }");
        var requirement = Requirement.of("package", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        assertThat(paths).containsExactlyInAnyOrder("weight", "dimension.width", "dimension.height");
    }

    @Test
    void extractPaths_renamedNestedDimension() {
        // @require(field: "{ weight, size: dimension.{ width, height } }")
        // Same paths regardless of output field naming
        var selection = FieldSelectionMapParser.parseFieldSelectionMap(
            "{ weight, size: dimension.{ width, height } }");
        var requirement = Requirement.of("package", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        assertThat(paths).containsExactlyInAnyOrder("weight", "dimension.width", "dimension.height");
    }

    @Test
    void extractPaths_listWithObjectElement() {
        // @require(field: "dimensions[{ width, height }]")
        var selection = FieldSelectionMapParser.parseFieldSelectionMap("dimensions[{ width, height }]");
        var requirement = Requirement.of("dims", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        assertThat(paths).containsExactlyInAnyOrder("dimensions.width", "dimensions.height");
    }

    @Test
    void extractPaths_tripleNestedList() {
        // @require(field: "matrix[[[id]]]") - for [[[ID!]!]!]! type
        var selection = FieldSelectionMapParser.parseFieldSelectionMap("matrix[[[id]]]");
        var requirement = Requirement.of("ids", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        assertThat(paths).containsExactly("matrix.id");
    }

    @Test
    void extractPaths_listInListWithObject() {
        // @require(field: "parts[[{ id, name }]]") - for [[Part!]]! type
        var selection = FieldSelectionMapParser.parseFieldSelectionMap("parts[[{ id, name }]]");
        var requirement = Requirement.of("partData", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        assertThat(paths).containsExactlyInAnyOrder("parts.id", "parts.name");
    }

    @Test
    void extractPaths_coordinatesMapping() {
        // @require(field: "{ coordinates: coordinates[{ lat: x, lon: y }] }")
        // From spec example - location input mapping
        var selection = FieldSelectionMapParser.parseFieldSelectionMap(
            "{ coordinates: coordinates[{ lat: x, lon: y }] }");
        var requirement = Requirement.of("location", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        assertThat(paths).containsExactlyInAnyOrder("coordinates.x", "coordinates.y");
    }

    @Test
    void extractPaths_deepPathPrefixWithList() {
        // @require(field: "order.items[product.{ sku price }]")
        var selection = FieldSelectionMapParser.parseFieldSelectionMap(
            "order.items[product.{ sku price }]");
        var requirement = Requirement.of("orderItems", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        assertThat(paths).containsExactlyInAnyOrder("order.items.product.sku", "order.items.product.price");
    }

    // ====== Additional edge cases from FSM spec ======

    @Test
    void extractPaths_alternativesWithLeadingPipe() {
        // Optional leading pipe is allowed in spec: `|? SelectedValueEntry`
        var selection = FieldSelectionMapParser.parseFieldSelectionMap("| book.title | movie.name");
        var requirement = Requirement.of("title", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        assertThat(paths).containsExactlyInAnyOrder("book.title", "movie.name");
    }

    @Test
    void extractPaths_singleValueWithLeadingPipe() {
        // Single value with optional leading pipe
        var selection = FieldSelectionMapParser.parseFieldSelectionMap("| weight");
        var requirement = Requirement.of("w", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        assertThat(paths).containsExactly("weight");
    }

    @Test
    void extractPaths_initialPlusChainedInfixTypeConditions() {
        // @require(field: "<Content>.article<NewsArticle>.headline")
        var selection = FieldSelectionMapParser.parseFieldSelectionMap(
            "<Content>.article<NewsArticle>.headline");
        var requirement = Requirement.of("headline", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        // Initial type condition on path, infix on segment
        assertThat(paths).containsExactly("<Content>.<NewsArticle>.article.headline");
    }

    @Test
    void extractPaths_mixedAlternativeTypes() {
        // @require(field: "author.name | bookDetails.{ title isbn } | ids[bookId]")
        // Alternatives can mix paths, objects, and lists
        var selection = FieldSelectionMapParser.parseFieldSelectionMap(
            "author.name | bookDetails.{ title isbn } | ids[bookId]");
        var requirement = Requirement.of("data", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        assertThat(paths).containsExactlyInAnyOrder(
            "author.name",
            "bookDetails.title",
            "bookDetails.isbn",
            "ids.bookId"
        );
    }

    @Test
    void extractPaths_complexNestedObjectStructure() {
        // @require(field: "{ package: { weight: dimensions.weight, size: dimensions.{ width height } } }")
        var selection = FieldSelectionMapParser.parseFieldSelectionMap(
            "{ package: { weight: dimensions.weight, size: dimensions.{ width height } } }");
        var requirement = Requirement.of("packageInfo", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        assertThat(paths).containsExactlyInAnyOrder(
            "dimensions.weight",
            "dimensions.width",
            "dimensions.height"
        );
    }

    @Test
    void extractPaths_fieldNamesWithUnderscores() {
        // @require(field: "_private_field.nested_value")
        var selection = FieldSelectionMapParser.parseFieldSelectionMap("_private_field.nested_value");
        var requirement = Requirement.of("data", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        assertThat(paths).containsExactly("_private_field.nested_value");
    }

    @Test
    void extractPaths_fieldNamesWithNumbers() {
        // @require(field: "field1.value2")
        var selection = FieldSelectionMapParser.parseFieldSelectionMap("field1.value2");
        var requirement = Requirement.of("data", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        assertThat(paths).containsExactly("field1.value2");
    }

    @Test
    void extractPaths_infixTypeConditionInMiddleOfPath() {
        // @require(field: "foo.bar<Bar>.baz")
        var selection = FieldSelectionMapParser.parseFieldSelectionMap("foo.bar<Bar>.baz");
        var requirement = Requirement.of("data", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        assertThat(paths).containsExactly("foo.<Bar>.bar.baz");
    }

    @Test
    void extractPaths_listAlternativesWithTypeConditions() {
        // @require(field: "items[<Product>.sku | <Service>.code]")
        var selection = FieldSelectionMapParser.parseFieldSelectionMap("items[<Product>.sku | <Service>.code]");
        var requirement = Requirement.of("identifiers", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        assertThat(paths).containsExactlyInAnyOrder("items.<Product>.sku", "items.<Service>.code");
    }

    @Test
    void extractPaths_deeplyNestedTypeConditions() {
        // @require(field: "data[{ typeA: item<A>.value } | { typeB: item<B>.value[subItem<Sub>.name] }]")
        var selection = FieldSelectionMapParser.parseFieldSelectionMap(
            "data[{ typeA: item<A>.value } | { typeB: item<B>.value[subItem<Sub>.name] }]");
        var requirement = Requirement.of("values", selection);

        List<String> paths = pathsToStrings(requirement.extractPaths());

        assertThat(paths).containsExactlyInAnyOrder(
            "data.<A>.item.value",
            "data.<B>.item.value.<Sub>.subItem.name"
        );
    }
}
