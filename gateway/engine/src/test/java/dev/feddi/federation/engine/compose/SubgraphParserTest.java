package dev.feddi.federation.engine.compose;

import graphql.language.StringValue;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLAppliedDirectiveArgument;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for SubgraphParser.
 */
class SubgraphParserTest {
    
    private final SubgraphParser parser = new SubgraphParser();
    
    @Test
    void parsesBasicSchema() {
        String sdl = """
            type Query {
                hello: String
            }
            """;
        
        Subgraph subgraph = parser.parse("test", sdl);
        
        assertThat(subgraph.name()).isEqualTo("test");
        assertThat(subgraph.schema().getQueryType()).isNotNull();
        assertThat(subgraph.schema().getQueryType().getFieldDefinition("hello")).isNotNull();
    }
    
    @Test
    void parsesKeyDirective() {
        String sdl = """
            type Query {
                userById(id: ID!): User @lookup
            }
            
            type User @key(fields: "id") {
                id: ID!
                name: String
            }
            """;
        
        Subgraph subgraph = parser.parse("users", sdl);
        
        GraphQLObjectType userType = (GraphQLObjectType) subgraph.schema().getType("User");
        assertThat(userType).isNotNull();
        assertThat(userType.hasAppliedDirective("key")).isTrue();
        
        GraphQLAppliedDirective keyDirective = userType.getAppliedDirective("key");
        assertThat((Object) keyDirective.getArgument("fields").getValue()).isEqualTo("id");
    }
    
    @Test
    void parsesLookupDirective() {
        String sdl = """
            type Query {
                userById(id: ID!): User @lookup
            }
            
            type User @key(fields: "id") {
                id: ID!
                name: String
            }
            """;
        
        Subgraph subgraph = parser.parse("users", sdl);
        
        GraphQLObjectType queryType = subgraph.schema().getQueryType();
        GraphQLFieldDefinition lookupField = queryType.getFieldDefinition("userById");
        
        assertThat(lookupField.hasAppliedDirective("lookup")).isTrue();
    }
    
    @Test
    void parsesIsDirective() {
        String sdl = """
            type Query {
                productLookup(productId: ID! @is(field: "id")): Product @lookup
            }
            
            type Product @key(fields: "id") {
                id: ID!
                name: String
            }
            """;
        
        Subgraph subgraph = parser.parse("products", sdl);
        
        GraphQLFieldDefinition lookupField = subgraph.schema()
            .getQueryType()
            .getFieldDefinition("productLookup");
        
        GraphQLArgument arg = lookupField.getArgument("productId");
        assertThat(arg.hasAppliedDirective("is")).isTrue();
        
        GraphQLAppliedDirective isDirective = arg.getAppliedDirective("is");
        assertThat(getStringValue(isDirective.getArgument("field"))).isEqualTo("id");
    }
    
    @Test
    void parsesRequireDirective() {
        String sdl = """
            type Query {
                productById(id: ID!): Product @lookup
            }
            
            type Product @key(fields: "id") {
                id: ID!
                productCost: Int!
                priceCents(cost: Int! @require(field: "productCost")): Int
            }
            """;
        
        Subgraph subgraph = parser.parse("pricing", sdl);
        
        GraphQLObjectType productType = (GraphQLObjectType) subgraph.schema().getType("Product");
        GraphQLFieldDefinition priceField = productType.getFieldDefinition("priceCents");
        
        GraphQLArgument costArg = priceField.getArgument("cost");
        assertThat(costArg.hasAppliedDirective("require")).isTrue();
        
        GraphQLAppliedDirective requireDirective = costArg.getAppliedDirective("require");
        assertThat(getStringValue(requireDirective.getArgument("field"))).isEqualTo("productCost");
    }
    
    @Test
    void parsesInternalDirective() {
        String sdl = """
            type Query {
                lookups: InternalLookups! @internal
            }
            
            type InternalLookups @internal {
                userById(id: ID!): User @lookup
            }
            
            type User @key(fields: "id") {
                id: ID!
            }
            """;
        
        Subgraph subgraph = parser.parse("internal", sdl);
        
        GraphQLFieldDefinition lookupsField = subgraph.schema()
            .getQueryType()
            .getFieldDefinition("lookups");
        assertThat(lookupsField.hasAppliedDirective("internal")).isTrue();
        
        GraphQLObjectType internalType = (GraphQLObjectType) subgraph.schema().getType("InternalLookups");
        assertThat(internalType.hasAppliedDirective("internal")).isTrue();
    }
    
    @Test
    void parsesShareableDirective() {
        String sdl = """
            type Query {
                products: [Product]
            }
            
            type Product @key(fields: "id") {
                id: ID!
                name: String @shareable
            }
            """;
        
        Subgraph subgraph = parser.parse("products", sdl);
        
        GraphQLObjectType productType = (GraphQLObjectType) subgraph.schema().getType("Product");
        GraphQLFieldDefinition nameField = productType.getFieldDefinition("name");
        
        assertThat(nameField.hasAppliedDirective("shareable")).isTrue();
    }
    
    @Test
    void parsesExternalDirective() {
        String sdl = """
            type Query {
                products: [Product]
            }
            
            type Product @key(fields: "id") {
                id: ID!
                details: ProductDetails @external
            }
            
            type ProductDetails {
                code: String
            }
            """;
        
        Subgraph subgraph = parser.parse("products", sdl);
        
        GraphQLObjectType productType = (GraphQLObjectType) subgraph.schema().getType("Product");
        GraphQLFieldDefinition detailsField = productType.getFieldDefinition("details");
        
        assertThat(detailsField.hasAppliedDirective("external")).isTrue();
    }
    
    @Test
    void parsesCompositeKey() {
        String sdl = """
            type Query {
                enrollments: [Enrollment]
            }
            
            type Enrollment @key(fields: "studentId courseId") {
                studentId: ID!
                courseId: ID!
                grade: Float
            }
            """;
        
        Subgraph subgraph = parser.parse("enrollments", sdl);
        
        GraphQLObjectType enrollmentType = (GraphQLObjectType) subgraph.schema().getType("Enrollment");
        GraphQLAppliedDirective keyDirective = enrollmentType.getAppliedDirective("key");
        
        assertThat((Object) keyDirective.getArgument("fields").getValue()).isEqualTo("studentId courseId");
    }
    
    /**
     * Helper method to extract string value from a directive argument.
     * For FieldSelectionMap arguments (custom scalar), the value is a StringValue AST node.
     */
    private String getStringValue(GraphQLAppliedDirectiveArgument arg) {
        Object value = arg.getValue();
        if (value instanceof StringValue stringValue) {
            return stringValue.getValue();
        }
        return value != null ? value.toString() : null;
    }
}
