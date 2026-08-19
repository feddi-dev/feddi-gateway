package dev.feddi.federation.engine.plan;

import dev.feddi.federation.engine.graph.Graph;
import dev.feddi.federation.engine.planner.ExecutionPlan;
import dev.feddi.federation.engine.planner.ExecutionStep;
import dev.feddi.federation.engine.query.FieldSelection;
import dev.feddi.federation.engine.query.Operation;
import dev.feddi.federation.engine.planner.OperationPlanner;
import dev.feddi.federation.engine.testcase.SchemaDefinition;
import dev.feddi.federation.engine.testcase.TestCaseLoader;
import graphql.language.Document;
import graphql.language.OperationDefinition;
import graphql.parser.Parser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests verifying that ExecutionStep can generate correct GraphQL queries
 * from its OperationDefinition structure.
 */
class ExecutionStepGraphQLGenerationTest {
    
    private final Parser parser = new Parser();
    
    /**
     * Helper to create an OperationDefinition from a GraphQL query string.
     */
    private OperationDefinition parseOperation(String graphql) {
        Document doc = parser.parseDocument(graphql);
        return (OperationDefinition) doc.getDefinitions().get(0);
    }
    
    @Test
    @DisplayName("Test 1: Deep nesting - organizations -> teams -> projects -> tasks hierarchy")
    void deepNestedHierarchy_generatesCorrectGraphQL() throws IOException {
        // Load the complex_nested schema
        TestCaseLoader loader = new TestCaseLoader();
        SchemaDefinition schemaDef = loader.loadSchemaFromClasspath("schemas/complex_nested/schema.yaml");
        Graph graph = schemaDef.graph();
        
        // Create a query with deep nesting
        Operation query = Operation.of("Query", List.of(
            FieldSelection.withSelections("organizations", List.of(
                FieldSelection.leaf("name"),
                FieldSelection.withSelections("teams", List.of(
                    FieldSelection.leaf("teamName"),
                    FieldSelection.withSelections("projects", List.of(
                        FieldSelection.leaf("projectName"),
                        FieldSelection.withSelections("tasks", List.of(
                            FieldSelection.leaf("title"),
                            FieldSelection.leaf("status")
                        ))
                    ))
                ))
            ))
        ));
        
        // Plan the query
        OperationPlanner planner = new OperationPlanner(graph);
        ExecutionPlan plan = planner.plan(query);
        
        // Find the first step (orgs subgraph)
        ExecutionStep orgsStep = plan.steps().stream()
            .filter(s -> s.subgraph().equals("orgs"))
            .findFirst()
            .orElseThrow();
        
        // Generate GraphQL and verify structure
        String graphql = orgsStep.toGraphQL();
        
        // Should have proper nesting: organizations { name teams { teamName teamId } }
        assertThat(graphql).contains("organizations");
        assertThat(graphql).contains("name");
        assertThat(graphql).contains("teams");
        assertThat(graphql).contains("teamName");
        
        // Verify nesting structure (teams should be inside organizations)
        int orgsPos = graphql.indexOf("organizations");
        int teamsPos = graphql.indexOf("teams");
        int namePos = graphql.indexOf("name");
        assertThat(teamsPos).isGreaterThan(orgsPos);
        assertThat(namePos).isGreaterThan(orgsPos);
        
        // Verify braces indicate proper nesting
        assertThat(graphql).matches("(?s).*organizations\\s*\\{.*teams\\s*\\{.*\\}.*\\}.*");
    }
    
    @Test
    @DisplayName("Test 2: Multiple root fields with mixed depths")
    void multipleRootFieldsMixedDepths_generatesCorrectGraphQL() {
        // Create an OperationDefinition with mixed nesting depths
        OperationDefinition operation = parseOperation("""
            {
                id
                user {
                    name
                    profile {
                        bio
                    }
                }
                status
            }
            """);
        
        // Create an ExecutionStep directly with the OperationDefinition
        ExecutionStep step = new ExecutionStep(
            1,
            "main",
            operation,
            List.of(),
            List.of(),  // parallelWith
            Map.of(),
            false,
            Set.of(),
            Set.of(),
            Set.of(),
            Map.of()
        );
        
        // Generate GraphQL
        String graphql = step.toGraphQL();
        
        // Verify all fields present
        assertThat(graphql).contains("id");
        assertThat(graphql).contains("user");
        assertThat(graphql).contains("name");
        assertThat(graphql).contains("profile");
        assertThat(graphql).contains("bio");
        assertThat(graphql).contains("status");
        
        // Verify nesting structure
        // id and status should be at root level (not inside braces of other fields)
        // user should contain name and profile
        // profile should contain bio
        assertThat(graphql).matches("(?s).*user\\s*\\{.*name.*profile\\s*\\{.*bio.*\\}.*\\}.*");
    }
    
    @Test
    @DisplayName("Test 3: Auto-included key fields appear at correct nesting level")
    void autoIncludedKeyFields_generatesCorrectGraphQL() throws IOException {
        // Load the products_reviews schema
        TestCaseLoader loader = new TestCaseLoader();
        SchemaDefinition schemaDef = loader.loadSchemaFromClasspath("schemas/products_reviews/schema.yaml");
        Graph graph = schemaDef.graph();
        
        // Create a query that requires a lookup (triggers auto-include of 'id')
        // Query: products { name rating }
        // The 'rating' field requires lookup to 'reviews' subgraph, 
        // which should auto-include 'id' as a key field in the products step
        Operation query = Operation.of("Query", List.of(
            FieldSelection.withSelections("products", List.of(
                FieldSelection.leaf("name"),
                FieldSelection.leaf("rating")  // This is in reviews subgraph
            ))
        ));
        
        // Plan the query
        OperationPlanner planner = new OperationPlanner(graph);
        ExecutionPlan plan = planner.plan(query);
        
        // Find the products step (first step)
        ExecutionStep productsStep = plan.steps().stream()
            .filter(s -> s.subgraph().equals("products"))
            .findFirst()
            .orElseThrow();
        
        // Generate GraphQL
        String graphql = productsStep.toGraphQL();
        
        // Verify the auto-included 'id' field is present and at the correct level
        // It should be inside products: products { name id }
        assertThat(graphql).contains("products");
        assertThat(graphql).contains("name");
        assertThat(graphql).contains("id");  // Auto-included key field
        
        // Verify 'id' is inside products (not at root)
        assertThat(graphql).matches("(?s).*products\\s*\\{[^}]*id[^}]*\\}.*");
        assertThat(graphql).matches("(?s).*products\\s*\\{[^}]*name[^}]*\\}.*");
        
        // Verify the flattened fields also contain the auto-included id
        assertThat(productsStep.flattenedFields()).contains("products", "name", "id");
    }
    
    @Test
    @DisplayName("Test 4: OperationDefinition can be retrieved from ExecutionStep")
    void operationDefinitionAccessible() {
        // Create an OperationDefinition
        OperationDefinition operation = parseOperation("{ users { id name } }");
        
        // Create an ExecutionStep
        ExecutionStep step = new ExecutionStep(
            1,
            "users",
            operation,
            List.of(),
            List.of(),  // parallelWith
            Map.of(),
            false,
            Set.of(),
            Set.of(),
            Set.of(),
            Map.of()
        );
        
        // Verify operation is accessible
        assertThat(step.operation()).isNotNull();
        assertThat(step.operation().getOperation()).isEqualTo(OperationDefinition.Operation.QUERY);
        assertThat(step.operation().getSelectionSet()).isNotNull();
        assertThat(step.operation().getSelectionSet().getSelections()).hasSize(1);
        
        // Verify flattened fields
        assertThat(step.flattenedFields()).containsExactly("users", "id", "name");
    }
}
