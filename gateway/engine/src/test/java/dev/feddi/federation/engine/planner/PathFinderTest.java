package dev.feddi.federation.engine.planner;

import dev.feddi.federation.engine.graph.FieldMoveEdge;
import dev.feddi.federation.engine.graph.Graph;
import dev.feddi.federation.engine.graph.LookupArgument;
import dev.feddi.federation.engine.graph.Node;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the PathFinder algorithm.
 */
class PathFinderTest {

    private static final Logger log = LoggerFactory.getLogger(PathFinderTest.class);

    @Test
    @DisplayName("Should find direct path when field is in same subgraph")
    void findDirectPath() {
        // Build a simple graph
        Node queryNode = new Node("Query", "main");
        Node userNode = new Node("User", "main");
        
        Graph graph = Graph.builder()
            .root(queryNode)
            .addFieldEdge("users", queryNode, userNode)
            .addFieldEdge("id", userNode, userNode)
            .addFieldEdge("name", userNode, userNode)
            .build();
        
        PathFinder finder = new PathFinder(graph);
        
        // Start at Query, find path to "users"
        OperationPath startPath = OperationPath.startAt(queryNode);
        List<OperationPath> paths = finder.findPaths(startPath, "users");
        
        assertThat(paths)
            .hasSize(1)
            .first()
            .satisfies(path -> {
                assertThat(path.cost()).isEqualTo(1);
                assertThat(path.currentSubgraph()).isEqualTo("main");
            });
    }
    
    @Test
    @DisplayName("Should find indirect path via lookup")
    void findIndirectPath() {
        // Build a graph with two subgraphs
        Node queryNode = new Node("Query", "root");
        Node productMain = new Node("Product", "main");
        Node productReviews = new Node("Product", "reviews");
        
        Graph graph = Graph.builder()
            .root(queryNode)
            .addFieldEdge("products", queryNode, productMain)
            .addFieldEdge("id", productMain, productMain)
            .addLookupEdge("productById", productMain, productReviews, 10, List.of(LookupArgument.of("id", "id")))
            .addFieldEdge("rating", productReviews, productReviews)
            .build();
        
        PathFinder finder = new PathFinder(graph);
        
        // Start at Product/main, find path to "rating"
        OperationPath startPath = OperationPath.startAt(queryNode)
            .advance(new FieldMoveEdge("products", queryNode, productMain, 1));
        
        List<OperationPath> paths = finder.findPaths(startPath, "rating");
        
        assertThat(paths)
            .hasSize(1)
            .first()
            .satisfies(path -> {
                // Cost: 1 (products) + 10 (lookup) + 1 (rating) = 12
                assertThat(path.cost()).isEqualTo(12);
                assertThat(path.currentSubgraph()).isEqualTo("reviews");
            });
    }
    
    @Test
    @DisplayName("Should return empty list when field not found")
    void fieldNotFound() {
        Node queryNode = new Node("Query", "main");
        Node userNode = new Node("User", "main");
        
        Graph graph = Graph.builder()
            .root(queryNode)
            .addFieldEdge("users", queryNode, userNode)
            .addFieldEdge("id", userNode, userNode)
            .build();
        
        PathFinder finder = new PathFinder(graph);
        OperationPath startPath = OperationPath.startAt(queryNode)
            .advance(new FieldMoveEdge("users", queryNode, userNode, 1));
        
        List<OperationPath> paths = finder.findPaths(startPath, "nonexistent");
        
        assertThat(paths).isEmpty();
    }
    
    @Test
    @DisplayName("Should prefer lower cost path")
    void preferLowerCostPath() {
        Node queryNode = new Node("Query", "root");
        Node productMain = new Node("Product", "main");
        Node productFast = new Node("Product", "fast");
        Node productSlow = new Node("Product", "slow");
        
        Graph graph = Graph.builder()
            .root(queryNode)
            .addFieldEdge("products", queryNode, productMain)
            .addFieldEdge("id", productMain, productMain)
            // Fast path (cost 5)
            .addLookupEdge("productByIdFast", productMain, productFast, 5, List.of(LookupArgument.of("id", "id")))
            .addFieldEdge("price", productFast, productFast)
            // Slow path (cost 20)
            .addLookupEdge("productByIdSlow", productMain, productSlow, 20, List.of(LookupArgument.of("id", "id")))
            .addFieldEdge("price", productSlow, productSlow)
            .build();
        
        PathFinder finder = new PathFinder(graph);
        OperationPath startPath = OperationPath.startAt(queryNode)
            .advance(new FieldMoveEdge("products", queryNode, productMain, 1));
        
        List<OperationPath> paths = finder.findPaths(startPath, "price");
        
        // Should return only the best path(s)
        assertThat(paths)
            .isNotEmpty()
            .allSatisfy(path -> {
                // Should be the fast path: 1 + 5 + 1 = 7
                assertThat(path.cost()).isEqualTo(7);
                assertThat(path.currentSubgraph()).isEqualTo("fast");
            });
    }
    
    @Test
    @DisplayName("Should prevent cycles during indirect path finding")
    void preventCycles() {
        Node queryNode = new Node("Query", "root");
        Node userA = new Node("User", "a");
        Node userB = new Node("User", "b");

        Graph graph = Graph.builder()
            .root(queryNode)
            .addFieldEdge("users", queryNode, userA)
            .addFieldEdge("id", userA, userA)
            // Lookup: a -> b
            .addLookupEdge("userById", userA, userB, 10, List.of(LookupArgument.of("id", "id")))
            // Lookup: b -> a (potential cycle)
            .addLookupEdge("userById", userB, userA, 10, List.of(LookupArgument.of("id", "id")))
            .addFieldEdge("id", userB, userB)
            .addFieldEdge("email", userB, userB)
            .build();

        PathFinder finder = new PathFinder(graph);
        OperationPath startPath = OperationPath.startAt(queryNode)
            .advance(new FieldMoveEdge("users", queryNode, userA, 1));

        // Should find email via b, not loop back to a
        List<OperationPath> paths = finder.findPaths(startPath, "email");

        assertThat(paths)
            .hasSize(1)
            .first()
            .satisfies(path -> {
                assertThat(path.currentSubgraph()).isEqualTo("b");
                assertThat(path.getVisitedSubgraphs()).containsExactlyInAnyOrder("root", "a", "b");
            });
    }

    @Test
    @DisplayName("Should find path via union lookup with typeContext")
    void findPathViaUnionLookupWithTypeContext() {
        // Simulates: at Media/content with typeContext=Book, need to find rating in ratings subgraph
        Node queryNode = new Node("Query", "$root");
        Node mediaContent = new Node("Media", "content");
        Node bookContent = new Node("Book", "content");
        Node mediaRatings = new Node("Media", "ratings");
        Node bookRatings = new Node("Book", "ratings");

        Graph graph = Graph.builder()
            .root(queryNode)
            .addFieldEdge("mediaById", queryNode, mediaContent)
            // Book is a member of Media union
            .addTypeMemberOfUnions("Book", java.util.Set.of("Media"))
            // Book/content has id and title fields
            .addFieldEdge("id", bookContent, bookContent)
            .addFieldEdge("title", bookContent, bookContent)
            // Lookup edges from Media/content (union) to ratings subgraph
            .addLookupEdge("mediaById", mediaContent, mediaRatings, 10, List.of(LookupArgument.of("id", "id")))
            .addLookupEdge("mediaById", mediaContent, bookRatings, 10, List.of(LookupArgument.of("id", "id")))
            // Book/ratings has rating field
            .addFieldEdge("id", bookRatings, bookRatings)
            .addFieldEdge("rating", bookRatings, bookRatings)
            .build();

        // Debug: verify graph structure
        log.debug("Nodes: {}", graph.nodes());
        log.debug("Lookup edges from Media/content: {}", graph.lookupEdgesFrom(mediaContent).toList());
        log.debug("Unions for Book: {}", graph.getUnionsForType("Book"));
        log.debug("Field edges from Book/ratings: {}", graph.fieldEdgesFrom(bookRatings).toList());

        PathFinder finder = new PathFinder(graph);

        // Start at Media/content with typeContext=Book (inside inline fragment)
        OperationPath startPath = OperationPath.startAt(queryNode)
            .advance(new FieldMoveEdge("mediaById", queryNode, mediaContent, 1))
            .withTypeContext("Book");

        log.debug("Start path tail: {}", startPath.tail());
        log.debug("Start path typeContext: {}", startPath.typeContext());

        List<OperationPath> paths = finder.findPaths(startPath, "rating");
        log.debug("Found paths: {}", paths);

        assertThat(paths)
            .as("Should find path to rating via union lookup")
            .isNotEmpty()
            .first()
            .satisfies(path -> {
                assertThat(path.currentSubgraph()).isEqualTo("ratings");
            });
    }
}
