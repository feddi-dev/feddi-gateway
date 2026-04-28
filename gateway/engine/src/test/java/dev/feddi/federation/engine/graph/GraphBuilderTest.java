package dev.feddi.federation.engine.graph;

import dev.feddi.federation.engine.graph.GraphBuilder;
import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.graph.Graph;
import dev.feddi.federation.engine.graph.LookupMoveEdge;
import dev.feddi.federation.engine.graph.Node;
import dev.feddi.federation.engine.parser.FieldSelectionMap.FieldSelection;
import dev.feddi.federation.engine.parser.FieldSelectionMap.FieldSelectionSet;
import dev.feddi.federation.engine.parser.FieldSelectionMap.InlineFragment;
import dev.feddi.federation.engine.parser.FieldSelectionMap.SelectionItem;
import dev.feddi.federation.engine.compose.SubgraphParser;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static dev.feddi.federation.engine.graph.GraphBuilder.ROOT_SUBGRAPH;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for GraphBuilder.
 */
class GraphBuilderTest {

    private final SubgraphParser parser = new SubgraphParser();
    private final GraphBuilder graphBuilder = new GraphBuilder();

    /**
     * Helper method to extract top-level field names from a FieldSelectionSet.
     * For field selections, adds the field name.
     * For inline fragments, recurses into the fragment's selections.
     */
    private Set<String> extractTopLevelFieldNames(FieldSelectionSet selectionSet) {
        Set<String> fieldNames = new HashSet<>();
        collectFieldNames(selectionSet.items(), fieldNames);
        return fieldNames;
    }

    private void collectFieldNames(List<SelectionItem> items, Set<String> fieldNames) {
        for (SelectionItem item : items) {
            switch (item) {
                case FieldSelection field -> fieldNames.add(field.fieldName());
                case InlineFragment fragment -> collectFieldNames(fragment.selections(), fieldNames);
            }
        }
    }
    
    @Test
    void buildsGraphFromSingleSubgraph() {
        String sdl = """
            type Query {
                users: [User]
            }

            type User {
                id: ID!
                name: String
            }
            """;

        Subgraph subgraph = parser.parse("users", sdl);
        Graph graph = graphBuilder.build(List.of(subgraph));

        // Root should be the unified root node
        assertThat(graph.getRootNode("Query")).isEqualTo(new Node("Query", ROOT_SUBGRAPH));

        // Graph should contain unified root, subgraph Query node, and User node
        assertThat(graph.nodes()).contains(
            new Node("Query", ROOT_SUBGRAPH),
            new Node("Query", "users"),
            new Node("User", "users")
        );

        // Unified root should have edge to User (via users field)
        Node rootNode = new Node("Query", ROOT_SUBGRAPH);
        assertThat(graph.findFieldEdge(rootNode, "users"))
            .isPresent()
            .hasValueSatisfying(edge -> {
                assertThat(edge.target()).isEqualTo(new Node("User", "users"));
            });
    }
    
    @Test
    void createsFieldMoveEdges() {
        String sdl = """
            type Query {
                users: [User]
            }
            
            type User {
                id: ID!
                name: String
                profile: Profile
            }
            
            type Profile {
                bio: String
            }
            """;
        
        Subgraph subgraph = parser.parse("users", sdl);
        Graph graph = graphBuilder.build(List.of(subgraph));
        
        Node queryNode = new Node("Query", "users");
        Node userNode = new Node("User", "users");
        Node profileNode = new Node("Profile", "users");
        
        // Query.users -> User
        assertThat(graph.findFieldEdge(queryNode, "users"))
            .isPresent()
            .hasValueSatisfying(edge -> {
                assertThat(edge.target()).isEqualTo(userNode);
            });
        
        // User.profile -> Profile
        assertThat(graph.findFieldEdge(userNode, "profile"))
            .isPresent()
            .hasValueSatisfying(edge -> {
                assertThat(edge.target()).isEqualTo(profileNode);
            });
    }
    
    @Test
    void createsLookupMoveEdges() {
        String usersSDL = """
            type Query {
                users: [User]
                userById(id: ID!): User @lookup
            }
            
            type User @key(fields: "id") {
                id: ID!
                name: String
            }
            """;
        
        String ordersSDL = """
            type Query {
                orders: [Order]
            }
            
            type User @key(fields: "id") {
                id: ID!
                orders: [Order]
            }
            
            type Order {
                id: ID!
                total: Float
            }
            """;
        
        Subgraph usersSubgraph = parser.parse("users", usersSDL);
        Subgraph ordersSubgraph = parser.parse("orders", ordersSDL);
        
        Graph graph = graphBuilder.build(List.of(usersSubgraph, ordersSubgraph));
        
        // There should be a lookup edge from User/orders to User/users
        Node userInOrders = new Node("User", "orders");
        
        List<LookupMoveEdge> lookupEdges = graph.lookupEdgesFrom(userInOrders).toList();
        assertThat(lookupEdges).hasSize(1);
        
        LookupMoveEdge lookupEdge = lookupEdges.get(0);
        assertThat(lookupEdge.target()).isEqualTo(new Node("User", "users"));
        assertThat(lookupEdge.fieldName()).isEqualTo("userById");
        assertThat(lookupEdge.lookupArguments())
            .extracting(arg -> arg.path().segments().get(0).fieldName())
            .contains("id");
    }
    
    @Test
    void extractsLookupArgumentsFromIsDirective() {
        String sdl = """
            type Query {
                productLookup(productId: ID! @is(field: "id")): Product @lookup
            }
            
            type Product @key(fields: "id") {
                id: ID!
                name: String
            }
            """;
        
        String extendingSDL = """
            type Query {
                inventory: [Inventory]
            }
            
            type Product @key(fields: "id") {
                id: ID!
                stock: Int
            }
            
            type Inventory {
                productId: ID!
            }
            """;
        
        Subgraph productsSubgraph = parser.parse("products", sdl);
        Subgraph inventorySubgraph = parser.parse("inventory", extendingSDL);
        
        Graph graph = graphBuilder.build(List.of(productsSubgraph, inventorySubgraph));
        
        Node productInInventory = new Node("Product", "inventory");
        List<LookupMoveEdge> lookupEdges = graph.lookupEdgesFrom(productInInventory).toList();
        
        assertThat(lookupEdges).hasSize(1);
        assertThat(lookupEdges.get(0).lookupArguments())
            .extracting(arg -> arg.path().segments().get(0).fieldName())
            .contains("id");
    }

    @Test
    void handlesMultipleSubgraphsWithSameType() {
        String subgraphA = """
            type Query {
                userById(id: ID!): User @lookup
            }
            
            type User @key(fields: "id") {
                id: ID!
                name: String
            }
            """;
        
        String subgraphB = """
            type Query {
                userById(id: ID!): User @lookup
            }
            
            type User @key(fields: "id") {
                id: ID!
                email: String
            }
            """;
        
        Subgraph a = parser.parse("a", subgraphA);
        Subgraph b = parser.parse("b", subgraphB);
        
        Graph graph = graphBuilder.build(List.of(a, b));
        
        // Both subgraphs should have User nodes
        assertThat(graph.findNode("User", "a")).isPresent();
        assertThat(graph.findNode("User", "b")).isPresent();
        
        // There should be lookup edges between them
        Node userInA = new Node("User", "a");
        Node userInB = new Node("User", "b");
        
        assertThat(graph.lookupEdgesFrom(userInA).anyMatch(e -> e.target().equals(userInB))).isTrue();
        assertThat(graph.lookupEdgesFrom(userInB).anyMatch(e -> e.target().equals(userInA))).isTrue();
    }
    
    // ==================== Additional Edge Case Tests ====================
    
    @Test
    void scalarFieldsCreateSelfReferentialEdges() {
        String sdl = """
            type Query {
                users: [User]
            }
            
            type User {
                id: ID!
                name: String
                age: Int
                score: Float
                active: Boolean
            }
            """;
        
        Subgraph subgraph = parser.parse("main", sdl);
        Graph graph = graphBuilder.build(List.of(subgraph));
        
        Node userNode = new Node("User", "main");
        
        // Scalar fields should have self-referential edges (target = source)
        assertThat(graph.findFieldEdge(userNode, "id"))
            .isPresent()
            .hasValueSatisfying(e -> assertThat(e.target()).isEqualTo(userNode));
        assertThat(graph.findFieldEdge(userNode, "name"))
            .isPresent()
            .hasValueSatisfying(e -> assertThat(e.target()).isEqualTo(userNode));
        assertThat(graph.findFieldEdge(userNode, "age"))
            .isPresent()
            .hasValueSatisfying(e -> assertThat(e.target()).isEqualTo(userNode));
        assertThat(graph.findFieldEdge(userNode, "score"))
            .isPresent()
            .hasValueSatisfying(e -> assertThat(e.target()).isEqualTo(userNode));
        assertThat(graph.findFieldEdge(userNode, "active"))
            .isPresent()
            .hasValueSatisfying(e -> assertThat(e.target()).isEqualTo(userNode));
        
        // Graph should have unified root, Query, and User nodes (no scalar nodes)
        assertThat(graph.nodes())
            .containsExactlyInAnyOrder(
                new Node("Query", ROOT_SUBGRAPH),
                new Node("Query", "main"),
                new Node("User", "main")
            );
    }
    
    // Note: @require is not allowed on lookup field arguments per the spec.
    // All arguments of a lookup field together make up the key fields for an entity.
    // @require is for non-lookup fields that need data from other schemas.

    @Test
    void lookupEdgesOnlyFromOtherSubgraphs() {
        // Note: @is is omitted because argument name "id" matches the field name
        String productsSDL = """
            type Query {
                products: [Product]
                productById(id: ID!): Product @lookup
            }

            type Product @key(fields: "id") {
                id: ID!
                name: String
            }
            """;

        Subgraph products = parser.parse("products", productsSDL);
        Graph graph = graphBuilder.build(List.of(products));

        Node productNode = new Node("Product", "products");

        // No lookup edges should exist when there's only one subgraph
        // (can't lookup into yourself)
        List<LookupMoveEdge> lookupEdges = graph.lookupEdgesFrom(productNode).toList();
        assertThat(lookupEdges).isEmpty();
    }
    
    @Test
    void unifiedRootHasEdgesToAllSubgraphQueryFields() {
        // First subgraph with Query
        String firstSDL = """
            type Query {
                users: [User]
            }

            type User {
                id: ID!
                name: String
            }
            """;

        // Second subgraph also with Query
        String secondSDL = """
            type Query {
                orders: [Order]
            }

            type Order {
                id: ID!
            }
            """;

        Subgraph first = parser.parse("first", firstSDL);
        Subgraph second = parser.parse("second", secondSDL);

        // Build graph - order shouldn't matter now
        Graph graph = graphBuilder.build(List.of(first, second));

        // Root should always be the unified root node
        assertThat(graph.getRootNode("Query")).isEqualTo(new Node("Query", ROOT_SUBGRAPH));

        // Unified root should have edges to BOTH subgraphs' Query fields
        Node rootNode = new Node("Query", ROOT_SUBGRAPH);

        // Edge to User from first subgraph
        assertThat(graph.findFieldEdge(rootNode, "users"))
            .isPresent()
            .hasValueSatisfying(edge -> {
                assertThat(edge.target()).isEqualTo(new Node("User", "first"));
            });

        // Edge to Order from second subgraph
        assertThat(graph.findFieldEdge(rootNode, "orders"))
            .isPresent()
            .hasValueSatisfying(edge -> {
                assertThat(edge.target()).isEqualTo(new Node("Order", "second"));
            });

        // Verify order doesn't affect root node - it's always unified
        Graph graph2 = graphBuilder.build(List.of(second, first));
        assertThat(graph2.getRootNode("Query")).isEqualTo(new Node("Query", ROOT_SUBGRAPH));

        // Both fields should still be accessible from unified root
        assertThat(graph2.findFieldEdge(rootNode, "users")).isPresent();
        assertThat(graph2.findFieldEdge(rootNode, "orders")).isPresent();
    }
    
    @Test
    void multiHopScenario() {
        // Note: @is is omitted where argument name matches field name
        String usersSDL = """
            type Query {
                users: [User]
            }

            type User @key(fields: "id") {
                id: ID!
                name: String
            }
            """;

        String ordersSDL = """
            type Query {
                userById(id: ID!): User @lookup
            }

            type User @key(fields: "id") {
                id: ID!
                orders: [Order]
            }

            type Order @key(fields: "orderId") {
                orderId: ID!
                total: Float
            }
            """;

        String fulfillmentSDL = """
            type Query {
                orderById(orderId: ID!): Order @lookup
            }

            type Order @key(fields: "orderId") {
                orderId: ID!
                trackingNumber: String
                status: String
            }
            """;
        
        Subgraph users = parser.parse("users", usersSDL);
        Subgraph orders = parser.parse("orders", ordersSDL);
        Subgraph fulfillment = parser.parse("fulfillment", fulfillmentSDL);
        
        Graph graph = graphBuilder.build(List.of(users, orders, fulfillment));
        
        // Verify nodes exist
        assertThat(graph.findNode("User", "users")).isPresent();
        assertThat(graph.findNode("User", "orders")).isPresent();
        assertThat(graph.findNode("Order", "orders")).isPresent();
        assertThat(graph.findNode("Order", "fulfillment")).isPresent();
        
        // Verify lookup paths exist
        // User/users -> User/orders (via userById)
        Node userInUsers = new Node("User", "users");
        List<LookupMoveEdge> userLookups = graph.lookupEdgesFrom(userInUsers).toList();
        assertThat(userLookups)
            .anyMatch(e -> e.target().equals(new Node("User", "orders")));
        
        // Order/orders -> Order/fulfillment (via orderById)
        Node orderInOrders = new Node("Order", "orders");
        List<LookupMoveEdge> orderLookups = graph.lookupEdgesFrom(orderInOrders).toList();
        assertThat(orderLookups)
            .anyMatch(e -> e.target().equals(new Node("Order", "fulfillment")));
    }
    
    @Test
    void fieldEdgesForObjectTypeFields() {
        String sdl = """
            type Query {
                products: [Product]
            }
            
            type Product {
                id: ID!
                reviews: [Review]
                category: Category
            }
            
            type Review {
                stars: Int
                text: String
            }
            
            type Category {
                name: String
                parent: Category
            }
            """;
        
        Subgraph subgraph = parser.parse("main", sdl);
        Graph graph = graphBuilder.build(List.of(subgraph));
        
        Node productNode = new Node("Product", "main");
        Node reviewNode = new Node("Review", "main");
        Node categoryNode = new Node("Category", "main");
        
        // Product.reviews -> Review
        assertThat(graph.findFieldEdge(productNode, "reviews"))
            .isPresent()
            .hasValueSatisfying(e -> assertThat(e.target()).isEqualTo(reviewNode));
        
        // Product.category -> Category
        assertThat(graph.findFieldEdge(productNode, "category"))
            .isPresent()
            .hasValueSatisfying(e -> assertThat(e.target()).isEqualTo(categoryNode));
        
        // Category.parent -> Category (self-referential)
        assertThat(graph.findFieldEdge(categoryNode, "parent"))
            .isPresent()
            .hasValueSatisfying(e -> assertThat(e.target()).isEqualTo(categoryNode));
    }
    
    @Test
    void bidirectionalLookupsBetweenSubgraphs() {
        // Note: @is is omitted because argument name "id" matches field name
        String subgraphA = """
            type Query {
                userByIdA(id: ID!): User @lookup
            }

            type User @key(fields: "id") {
                id: ID!
                nameA: String
            }
            """;

        String subgraphB = """
            type Query {
                userByIdB(id: ID!): User @lookup
            }

            type User @key(fields: "id") {
                id: ID!
                nameB: String
            }
            """;
        
        Subgraph a = parser.parse("a", subgraphA);
        Subgraph b = parser.parse("b", subgraphB);
        
        Graph graph = graphBuilder.build(List.of(a, b));
        
        Node userInA = new Node("User", "a");
        Node userInB = new Node("User", "b");
        
        // A -> B via userByIdA
        Set<String> aToB = graph.lookupEdgesFrom(userInA)
            .filter(e -> e.target().equals(userInB))
            .map(LookupMoveEdge::fieldName)
            .collect(Collectors.toSet());
        assertThat(aToB).contains("userByIdB");
        
        // B -> A via userByIdB
        Set<String> bToA = graph.lookupEdgesFrom(userInB)
            .filter(e -> e.target().equals(userInA))
            .map(LookupMoveEdge::fieldName)
            .collect(Collectors.toSet());
        assertThat(bToA).contains("userByIdA");
    }

    @Test
    void unionLookupCreatesEdgesToMemberTypes() {
        // Note: @is is omitted because argument name "id" matches field name
        String contentSDL = """
            type Query {
                mediaById(id: ID!): Media @lookup
            }

            union Media = Book | Movie

            type Book @key(fields: "id") {
                id: ID!
                title: String
            }

            type Movie @key(fields: "id") {
                id: ID!
                title: String
            }
            """;

        String ratingsSDL = """
            type Query {
                mediaById(id: ID!): Media @lookup
            }

            union Media = Book | Movie

            type Book @key(fields: "id") {
                id: ID!
                rating: Float
            }

            type Movie @key(fields: "id") {
                id: ID!
                rating: Float
            }
            """;

        Subgraph content = parser.parse("content", contentSDL);
        Subgraph ratings = parser.parse("ratings", ratingsSDL);

        Graph graph = graphBuilder.build(List.of(content, ratings));

        // Verify union nodes exist
        assertThat(graph.findNode("Media", "content")).isPresent();
        assertThat(graph.findNode("Media", "ratings")).isPresent();

        // Verify member type nodes exist
        assertThat(graph.findNode("Book", "content")).isPresent();
        assertThat(graph.findNode("Book", "ratings")).isPresent();
        assertThat(graph.findNode("Movie", "content")).isPresent();
        assertThat(graph.findNode("Movie", "ratings")).isPresent();

        // Verify union membership is tracked
        assertThat(graph.getUnionsForType("Book")).contains("Media");
        assertThat(graph.getUnionsForType("Movie")).contains("Media");

        // Verify lookup edges from union to member types
        Node mediaInContent = new Node("Media", "content");
        List<LookupMoveEdge> lookupEdges = graph.lookupEdgesFrom(mediaInContent).toList();

        // Should have edges to Media/ratings, Book/ratings, and Movie/ratings
        Set<Node> targets = lookupEdges.stream()
            .map(LookupMoveEdge::target)
            .collect(Collectors.toSet());

        assertThat(targets).contains(
            new Node("Media", "ratings"),
            new Node("Book", "ratings"),
            new Node("Movie", "ratings")
        );

        // Verify Book/ratings has field edge for rating
        Node bookInRatings = new Node("Book", "ratings");
        assertThat(graph.findFieldEdge(bookInRatings, "rating"))
            .as("Book in ratings should have rating field edge")
            .isPresent();

        // Verify Book/content has field edges for id and title
        Node bookInContent = new Node("Book", "content");
        assertThat(graph.findFieldEdge(bookInContent, "id"))
            .as("Book in content should have id field edge")
            .isPresent();
        assertThat(graph.findFieldEdge(bookInContent, "title"))
            .as("Book in content should have title field edge")
            .isPresent();
    }

    // ==================== @provides FieldSelectionSet Tests ====================

    @Test
    void providesWithSimpleFields() {
        String sdl = """
            type Query {
                products: [Product]
            }

            type Product {
                id: ID!
                author: Author @provides(fields: "name email")
            }

            type Author @key(fields: "id") {
                id: ID!
                name: String @external
                email: String @external
            }
            """;

        Subgraph subgraph = parser.parse("products", sdl);
        Graph graph = graphBuilder.build(List.of(subgraph));

        Node productNode = new Node("Product", "products");
        Node authorNode = new Node("Author", "products");

        var authorEdge = graph.findFieldEdge(productNode, "author");
        assertThat(authorEdge).isPresent();

        Set<String> providedFields = extractTopLevelFieldNames(graph.getProvidedFields(authorEdge.get()));
        assertThat(providedFields).containsExactlyInAnyOrder("name", "email");
    }

    @Test
    void providesWithNestedSelection() {
        // @provides(fields: "author { name bio }") means we provide the "author" field
        // with nested "name" and "bio" selections
        String sdl = """
            type Query {
                reviews: [Review]
            }

            type Review {
                id: ID!
                product: Product @provides(fields: "author { name bio }")
            }

            type Product @key(fields: "id") {
                id: ID!
                author: Author @external
            }

            type Author {
                name: String
                bio: String
            }
            """;

        Subgraph subgraph = parser.parse("reviews", sdl);
        Graph graph = graphBuilder.build(List.of(subgraph));

        Node reviewNode = new Node("Review", "reviews");
        Node productNode = new Node("Product", "reviews");

        var productEdge = graph.findFieldEdge(reviewNode, "product");
        assertThat(productEdge).isPresent();

        // The top-level provided field is "author"
        Set<String> providedFields = extractTopLevelFieldNames(graph.getProvidedFields(productEdge.get()));
        assertThat(providedFields).containsExactly("author");
    }

    @Test
    void providesWithDeeplyNestedSelection() {
        String sdl = """
            type Query {
                orders: [Order]
            }

            type Order {
                id: ID!
                customer: Customer @provides(fields: "profile { address { city country } }")
            }

            type Customer @key(fields: "id") {
                id: ID!
                profile: Profile @external
            }

            type Profile {
                address: Address
            }

            type Address {
                city: String
                country: String
            }
            """;

        Subgraph subgraph = parser.parse("orders", sdl);
        Graph graph = graphBuilder.build(List.of(subgraph));

        Node orderNode = new Node("Order", "orders");
        Node customerNode = new Node("Customer", "orders");

        var customerEdge = graph.findFieldEdge(orderNode, "customer");
        assertThat(customerEdge).isPresent();

        // The top-level provided field is "profile"
        Set<String> providedFields = extractTopLevelFieldNames(graph.getProvidedFields(customerEdge.get()));
        assertThat(providedFields).containsExactly("profile");
    }

    @Test
    void providesWithInlineFragment() {
        String sdl = """
            type Query {
                media: [Media]
            }

            type Media {
                id: ID!
                content: Content @provides(fields: "... on Book { isbn pages } ... on Movie { duration }")
            }

            interface Content @key(fields: "id") {
                id: ID!
            }

            type Book implements Content @key(fields: "id") {
                id: ID!
                isbn: String @external
                pages: Int @external
            }

            type Movie implements Content @key(fields: "id") {
                id: ID!
                duration: Int @external
            }
            """;

        Subgraph subgraph = parser.parse("media", sdl);
        Graph graph = graphBuilder.build(List.of(subgraph));

        Node mediaNode = new Node("Media", "media");
        Node contentNode = new Node("Content", "media");

        var contentEdge = graph.findFieldEdge(mediaNode, "content");
        assertThat(contentEdge).isPresent();

        // Fields from inline fragments: isbn, pages, duration
        Set<String> providedFields = extractTopLevelFieldNames(graph.getProvidedFields(contentEdge.get()));
        assertThat(providedFields).containsExactlyInAnyOrder("isbn", "pages", "duration");
    }

    @Test
    void providesWithMixedSelectionsAndInlineFragments() {
        String sdl = """
            type Query {
                items: [Item]
            }

            type Item {
                id: ID!
                entity: Entity @provides(fields: "name ... on Product { sku price } ... on Service { rate }")
            }

            interface Entity @key(fields: "id") {
                id: ID!
                name: String @external
            }

            type Product implements Entity @key(fields: "id") {
                id: ID!
                name: String @external
                sku: String @external
                price: Float @external
            }

            type Service implements Entity @key(fields: "id") {
                id: ID!
                name: String @external
                rate: Float @external
            }
            """;

        Subgraph subgraph = parser.parse("items", sdl);
        Graph graph = graphBuilder.build(List.of(subgraph));

        Node itemNode = new Node("Item", "items");
        Node entityNode = new Node("Entity", "items");

        var entityEdge = graph.findFieldEdge(itemNode, "entity");
        assertThat(entityEdge).isPresent();

        // "name" at top level, plus fields from inline fragments
        Set<String> providedFields = extractTopLevelFieldNames(graph.getProvidedFields(entityEdge.get()));
        assertThat(providedFields).containsExactlyInAnyOrder("name", "sku", "price", "rate");
    }

    @Test
    void providesWithMultipleFieldsAndNestedSelections() {
        String sdl = """
            type Query {
                posts: [Post]
            }

            type Post {
                id: ID!
                author: User @provides(fields: "name profile { avatar } settings { theme }")
            }

            type User @key(fields: "id") {
                id: ID!
                name: String @external
                profile: Profile @external
                settings: Settings @external
            }

            type Profile {
                avatar: String
            }

            type Settings {
                theme: String
            }
            """;

        Subgraph subgraph = parser.parse("posts", sdl);
        Graph graph = graphBuilder.build(List.of(subgraph));

        Node postNode = new Node("Post", "posts");
        Node userNode = new Node("User", "posts");

        var authorEdge = graph.findFieldEdge(postNode, "author");
        assertThat(authorEdge).isPresent();

        // Top-level fields: name, profile, settings
        Set<String> providedFields = extractTopLevelFieldNames(graph.getProvidedFields(authorEdge.get()));
        assertThat(providedFields).containsExactlyInAnyOrder("name", "profile", "settings");
    }

    @Test
    void providesOnInterfaceField() {
        String sdl = """
            type Query {
                nodes: [Node]
            }

            interface Node {
                id: ID!
                owner: User @provides(fields: "email displayName")
            }

            type Article implements Node {
                id: ID!
                title: String
                owner: User @provides(fields: "email displayName")
            }

            type User @key(fields: "id") {
                id: ID!
                email: String @external
                displayName: String @external
            }
            """;

        Subgraph subgraph = parser.parse("content", sdl);
        Graph graph = graphBuilder.build(List.of(subgraph));

        // Check interface field edge
        Node nodeNode = new Node("Node", "content");
        Node userNode = new Node("User", "content");

        var ownerEdgeFromInterface = graph.findFieldEdge(nodeNode, "owner");
        assertThat(ownerEdgeFromInterface).isPresent();
        assertThat(extractTopLevelFieldNames(graph.getProvidedFields(ownerEdgeFromInterface.get())))
            .containsExactlyInAnyOrder("email", "displayName");

        // Check implementing type field edge
        Node articleNode = new Node("Article", "content");
        var ownerEdgeFromArticle = graph.findFieldEdge(articleNode, "owner");
        assertThat(ownerEdgeFromArticle).isPresent();
        assertThat(extractTopLevelFieldNames(graph.getProvidedFields(ownerEdgeFromArticle.get())))
            .containsExactlyInAnyOrder("email", "displayName");
    }

    @Test
    void providesPreservesNestedStructure() {
        // @provides(fields: "author { name bio }")
        // Verify the full FieldSelectionSet structure is preserved
        String sdl = """
            type Query {
                reviews: [Review]
            }

            type Review {
                id: ID!
                product: Product @provides(fields: "author { name bio }")
            }

            type Product @key(fields: "id") {
                id: ID!
                author: Author @external
            }

            type Author {
                name: String
                bio: String
            }
            """;

        Subgraph subgraph = parser.parse("reviews", sdl);
        Graph graph = graphBuilder.build(List.of(subgraph));

        Node reviewNode = new Node("Review", "reviews");
        var productEdge = graph.findFieldEdge(reviewNode, "product");
        assertThat(productEdge).isPresent();

        FieldSelectionSet providedFields = graph.getProvidedFields(productEdge.get());

        // Verify the structure is preserved - "author" should have sub-selections
        assertThat(providedFields.items()).hasSize(1);
        assertThat(providedFields.items().get(0)).isInstanceOf(FieldSelection.class);

        FieldSelection authorSelection = (FieldSelection) providedFields.items().get(0);
        assertThat(authorSelection.fieldName()).isEqualTo("author");
        assertThat(authorSelection.hasSubSelections()).isTrue();
        assertThat(authorSelection.subSelections()).hasSize(2);

        // Verify nested fields
        Set<String> nestedFieldNames = authorSelection.subSelections().stream()
            .filter(item -> item instanceof FieldSelection)
            .map(item -> ((FieldSelection) item).fieldName())
            .collect(Collectors.toSet());
        assertThat(nestedFieldNames).containsExactlyInAnyOrder("name", "bio");
    }

    @Test
    void providesPreservesTypeCondition() {
        // @provides(fields: "... on Book { isbn } ... on Movie { duration }")
        // Verify type conditions are preserved
        String sdl = """
            type Query {
                media: [Media]
            }

            type Media {
                id: ID!
                content: Content @provides(fields: "... on Book { isbn } ... on Movie { duration }")
            }

            interface Content @key(fields: "id") {
                id: ID!
            }

            type Book implements Content @key(fields: "id") {
                id: ID!
                isbn: String @external
            }

            type Movie implements Content @key(fields: "id") {
                id: ID!
                duration: Int @external
            }
            """;

        Subgraph subgraph = parser.parse("media", sdl);
        Graph graph = graphBuilder.build(List.of(subgraph));

        Node mediaNode = new Node("Media", "media");
        var contentEdge = graph.findFieldEdge(mediaNode, "content");
        assertThat(contentEdge).isPresent();

        FieldSelectionSet providedFields = graph.getProvidedFields(contentEdge.get());

        // Verify the structure has two inline fragments
        assertThat(providedFields.items()).hasSize(2);
        assertThat(providedFields.items()).allMatch(item -> item instanceof InlineFragment);

        // Verify Book fragment
        InlineFragment bookFragment = (InlineFragment) providedFields.items().stream()
            .filter(item -> ((InlineFragment) item).typeName().equals("Book"))
            .findFirst()
            .orElseThrow();
        assertThat(bookFragment.selections()).hasSize(1);
        assertThat(((FieldSelection) bookFragment.selections().get(0)).fieldName()).isEqualTo("isbn");

        // Verify Movie fragment
        InlineFragment movieFragment = (InlineFragment) providedFields.items().stream()
            .filter(item -> ((InlineFragment) item).typeName().equals("Movie"))
            .findFirst()
            .orElseThrow();
        assertThat(movieFragment.selections()).hasSize(1);
        assertThat(((FieldSelection) movieFragment.selections().get(0)).fieldName()).isEqualTo("duration");
    }

    @Test
    void providesFieldWithTypeCondition() {
        // Verify that providesField() correctly handles type conditions
        String sdl = """
            type Query {
                media: [Media]
            }

            type Media {
                id: ID!
                content: Content @provides(fields: "... on Book { isbn pages } ... on Movie { duration }")
            }

            interface Content @key(fields: "id") {
                id: ID!
            }

            type Book implements Content @key(fields: "id") {
                id: ID!
                isbn: String @external
                pages: Int @external
            }

            type Movie implements Content @key(fields: "id") {
                id: ID!
                duration: Int @external
            }
            """;

        Subgraph subgraph = parser.parse("media", sdl);
        Graph graph = graphBuilder.build(List.of(subgraph));

        Node mediaNode = new Node("Media", "media");
        var contentEdge = graph.findFieldEdge(mediaNode, "content");
        assertThat(contentEdge).isPresent();

        // With typeContext "Book", should find isbn and pages but not duration
        assertThat(graph.providesField(contentEdge.get(), "isbn", "Book")).isTrue();
        assertThat(graph.providesField(contentEdge.get(), "pages", "Book")).isTrue();
        assertThat(graph.providesField(contentEdge.get(), "duration", "Book")).isFalse();

        // With typeContext "Movie", should find duration but not isbn/pages
        assertThat(graph.providesField(contentEdge.get(), "duration", "Movie")).isTrue();
        assertThat(graph.providesField(contentEdge.get(), "isbn", "Movie")).isFalse();
        assertThat(graph.providesField(contentEdge.get(), "pages", "Movie")).isFalse();

        // With null typeContext, should find all fields (matches any type condition)
        assertThat(graph.providesField(contentEdge.get(), "isbn", null)).isTrue();
        assertThat(graph.providesField(contentEdge.get(), "pages", null)).isTrue();
        assertThat(graph.providesField(contentEdge.get(), "duration", null)).isTrue();
    }
}
