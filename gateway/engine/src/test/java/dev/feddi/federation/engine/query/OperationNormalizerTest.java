package dev.feddi.federation.engine.query;

import graphql.language.AstPrinter;
import graphql.language.Document;
import graphql.language.Field;
import graphql.language.InlineFragment;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.language.VariableDefinition;
import graphql.parser.Parser;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Comprehensive tests for OperationNormalizer covering all GraphQL features.
 */
class OperationNormalizerTest {

    private static final GraphQLSchema TEST_SCHEMA = SchemaGenerator.createdMockedSchema("""
        type Query {
            user: User
            users: [User]
            node: Node
            searchResults: [SearchResult]
            products: [Product]
        }
        interface Node {
            id: ID!
        }
        type User implements Node {
            id: ID!
            name: String
            email: String
            age: Int
            username: String
            address: Address
        }
        type Address {
            street: String
            city: String
        }
        type Product implements Node {
            id: ID!
            name: String
            price: Float
        }
        type Post implements Node {
            id: ID!
            title: String
            content: String
        }
        type Comment implements Node {
            id: ID!
            text: String
        }
        union SearchResult = User | Post | Comment
        """);

    private OperationNormalizer normalizer;
    private Parser parser;

    @BeforeEach
    void setUp() {
        normalizer = OperationNormalizer.builder(TEST_SCHEMA).build();
        parser = new Parser();
    }

    private Document parse(String graphql) {
        return parser.parseDocument(graphql);
    }

    private OperationDefinition getOperation(Document document) {
        return document.getDefinitions().stream()
            .filter(d -> d instanceof OperationDefinition)
            .map(d -> (OperationDefinition) d)
            .findFirst()
            .orElseThrow();
    }

    private OperationDefinition getOperation(Document document, String name) {
        return document.getDefinitions().stream()
            .filter(d -> d instanceof OperationDefinition)
            .map(d -> (OperationDefinition) d)
            .filter(op -> name.equals(op.getName()))
            .findFirst()
            .orElseThrow();
    }

    @SuppressWarnings("rawtypes")
    private List<Selection> getSelections(Document document, String... path) {
        OperationDefinition op = getOperation(document);
        var selections = op.getSelectionSet().getSelections();

        for (String fieldName : path) {
            Field field = selections.stream()
                .filter(s -> s instanceof Field)
                .map(s -> (Field) s)
                .filter(f -> fieldName.equals(f.getName()) || fieldName.equals(f.getAlias()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Field not found: " + fieldName));
            selections = field.getSelectionSet().getSelections();
        }

        return selections;
    }

    /**
     * Helper method for SDL comparison verification.
     * Normalizes input and compares compact output to expected SDL.
     */
    private void assertNormalizedEquals(String input, String expectedOutput) {
        Document result = normalizer.normalize(parse(input));
        String actualOutput = AstPrinter.printAstCompact(result);
        String expectedCompact = AstPrinter.printAstCompact(parse(expectedOutput));
        assertThat(actualOutput).isEqualTo(expectedCompact);
    }

    /**
     * Helper method for SDL comparison verification with named operation.
     */
    private void assertNormalizedEquals(String input, String operationName, String expectedOutput) {
        Document result = normalizer.normalize(parse(input), operationName);
        String actualOutput = AstPrinter.printAstCompact(result);
        String expectedCompact = AstPrinter.printAstCompact(parse(expectedOutput));
        assertThat(actualOutput).isEqualTo(expectedCompact);
    }

    /**
     * Helper to compare a normalized document against expected SDL.
     * Handles formatting differences by parsing expected SDL and printing compactly.
     */
    private void assertSdlEquals(Document result, String expectedOutput) {
        String actualOutput = AstPrinter.printAstCompact(result);
        String expectedCompact = AstPrinter.printAstCompact(parse(expectedOutput));
        assertThat(actualOutput).isEqualTo(expectedCompact);
    }

    // ==================== FULL PIPELINE TESTS ====================

    @Nested
    @DisplayName("Full pipeline tests")
    class FullPipelineTests {

        @Test
        @DisplayName("Normalizes query with fragments, directives, duplicates, and sorting")
        void fullNormalization() {
            String input = """
                query {
                    user {
                        name
                        id @skip(if: true)
                        email
                        ...UserFields
                        name
                    }
                }
                fragment UserFields on User {
                    phone
                    address
                }
                """;

            Document result = normalizer.normalize(parse(input));
            OperationDefinition op = getOperation(result);

            // No fragment definitions in output
            assertThat(result.getDefinitions()).hasSize(1);

            Field userField = (Field) op.getSelectionSet().getSelections().get(0);
            var selections = userField.getSelectionSet().getSelections();

            // After normalization:
            // - id is removed (@skip(if: true))
            // - duplicate name is merged
            // - UserFields is inlined as ... on User, then simplified (user returns User)
            // - all fields are at the same level, sorted alphabetically

            assertThat(selections).hasSize(4);

            assertThat(((Field) selections.get(0)).getName()).isEqualTo("address");
            assertThat(((Field) selections.get(1)).getName()).isEqualTo("email");
            assertThat(((Field) selections.get(2)).getName()).isEqualTo("name");
            assertThat(((Field) selections.get(3)).getName()).isEqualTo("phone");

            // SDL verification
            assertSdlEquals(result, "{user {address email name phone}}");
        }

        @Test
        @DisplayName("Produces consistent output for equivalent inputs")
        void consistentOutput() {
            String input1 = "{ user { name id email } }";
            String input2 = "{ user { email name id } }";
            String input3 = "{ user { id email name } }";

            Document result1 = normalizer.normalize(parse(input1));
            Document result2 = normalizer.normalize(parse(input2));
            Document result3 = normalizer.normalize(parse(input3));

            String output1 = AstPrinter.printAstCompact(result1);
            String output2 = AstPrinter.printAstCompact(result2);
            String output3 = AstPrinter.printAstCompact(result3);

            assertThat(output1).isEqualTo(output2);
            assertThat(output2).isEqualTo(output3);
            assertSdlEquals(result1, "{ user { email id name } }");
        }
    }

    // ==================== CONFIGURATION OPTIONS ====================

    @Nested
    @DisplayName("Configuration options")
    class ConfigurationOptions {

        @Test
        @DisplayName("Can disable fragment inlining")
        void disableFragmentInlining() {
            OperationNormalizer noInlining = OperationNormalizer.builder(TEST_SCHEMA)
                .inlineFragments(false)
                .processSkipInclude(false)
                .deduplicateFields(false)
                .sortSelections(false)
                .build();

            String input = """
                query {
                    user { ...UserFields }
                }
                fragment UserFields on User { id }
                """;

            Document result = noInlining.normalize(parse(input));

            // Fragment definition should still exist
            assertThat(result.getDefinitions()).hasSize(2);
        }

        @Test
        @DisplayName("Can disable directive processing")
        void disableDirectiveProcessing() {
            OperationNormalizer noDirectives = OperationNormalizer.builder(TEST_SCHEMA)
                .inlineFragments(false)
                .processSkipInclude(false)
                .deduplicateFields(false)
                .sortSelections(false)
                .build();

            String input = "{ user { id @skip(if: true) } }";

            Document result = noDirectives.normalize(parse(input));
            OperationDefinition op = getOperation(result);
            Field userField = (Field) op.getSelectionSet().getSelections().get(0);
            Field idField = (Field) userField.getSelectionSet().getSelections().get(0);

            assertThat(idField.getDirectives()).hasSize(1);
            assertThat(idField.getDirectives().get(0).getName()).isEqualTo("skip");
        }

        @Test
        @DisplayName("Can disable field deduplication")
        void disableDeduplication() {
            OperationNormalizer noDedupe = OperationNormalizer.builder(TEST_SCHEMA)
                .inlineFragments(false)
                .processSkipInclude(false)
                .deduplicateFields(false)
                .sortSelections(false)
                .build();

            String input = "{ user { id id } }";

            Document result = noDedupe.normalize(parse(input));
            OperationDefinition op = getOperation(result);
            Field userField = (Field) op.getSelectionSet().getSelections().get(0);

            assertThat(userField.getSelectionSet().getSelections()).hasSize(2);
        }

        @Test
        @DisplayName("Can disable sorting")
        void disableSorting() {
            OperationNormalizer noSort = OperationNormalizer.builder(TEST_SCHEMA)
                .inlineFragments(false)
                .processSkipInclude(false)
                .deduplicateFields(false)
                .sortSelections(false)
                .build();

            String input = "{ user { z a m } }";

            Document result = noSort.normalize(parse(input));
            OperationDefinition op = getOperation(result);
            Field userField = (Field) op.getSelectionSet().getSelections().get(0);
            var selections = userField.getSelectionSet().getSelections();

            // Order preserved
            assertThat(((Field) selections.get(0)).getName()).isEqualTo("z");
            assertThat(((Field) selections.get(1)).getName()).isEqualTo("a");
            assertThat(((Field) selections.get(2)).getName()).isEqualTo("m");
        }

        @Test
        @DisplayName("Builder returns correct configuration")
        void builderConfiguration() {
            OperationNormalizer custom = OperationNormalizer.builder(TEST_SCHEMA)
                .inlineFragments(false)
                .processSkipInclude(true)
                .deduplicateFields(false)
                .sortSelections(true)
                .build();

            assertThat(custom.isInlineFragmentsEnabled()).isFalse();
            assertThat(custom.isProcessSkipIncludeEnabled()).isTrue();
            assertThat(custom.isDeduplicateFieldsEnabled()).isFalse();
            assertThat(custom.isSortSelectionsEnabled()).isTrue();
        }
    }

    // ==================== NAMED OPERATION NORMALIZATION ====================

    @Nested
    @DisplayName("Named operation normalization")
    class NamedOperationNormalization {

        @Test
        @DisplayName("Normalizes specific named operation")
        void normalizeNamedOperation() {
            String input = """
                query GetUser {
                    user { name id }
                }
                query GetPosts {
                    posts { title }
                }
                """;

            Document result = normalizer.normalize(parse(input), "GetUser");

            // Should have both operations (sorted)
            assertThat(result.getDefinitions()).hasSize(2);

            OperationDefinition getUserOp = result.getDefinitions().stream()
                .filter(d -> d instanceof OperationDefinition)
                .map(d -> (OperationDefinition) d)
                .filter(op -> "GetUser".equals(op.getName()))
                .findFirst()
                .orElseThrow();

            Field userField = (Field) getUserOp.getSelectionSet().getSelections().get(0);
            var selections = userField.getSelectionSet().getSelections();

            // Sorted: id, name
            assertThat(((Field) selections.get(0)).getName()).isEqualTo("id");
            assertThat(((Field) selections.get(1)).getName()).isEqualTo("name");
        }

        @Test
        @DisplayName("Throws when operation not found")
        void throwOnOperationNotFound() {
            String input = "query GetUser { user { id } }";

            assertThatThrownBy(() -> normalizer.normalize(parse(input), "GetPosts"))
                .isInstanceOf(OperationNormalizer.OperationNotFoundException.class)
                .hasMessageContaining("GetPosts");
        }

        @Test
        @DisplayName("Handles anonymous operation")
        void handleAnonymousOperation() {
            String input = "{ user { name id } }";

            Document result = normalizer.normalize(parse(input), null);
            OperationDefinition op = getOperation(result);
            Field userField = (Field) op.getSelectionSet().getSelections().get(0);
            var selections = userField.getSelectionSet().getSelections();

            assertThat(((Field) selections.get(0)).getName()).isEqualTo("id");
            assertThat(((Field) selections.get(1)).getName()).isEqualTo("name");

            // SDL verification
            assertSdlEquals(result, "{ user { id name } }");
        }
    }

    // ==================== REAL-WORLD QUERY EXAMPLES ====================

    @Nested
    @DisplayName("Real-world query examples")
    class RealWorldExamples {

        @Test
        @DisplayName("E-commerce product query")
        void ecommerceProductQuery() {
            String input = """
                query GetProducts($categoryId: ID!) {
                    products(categoryId: $categoryId) {
                        ...ProductInfo
                        reviews @include(if: true) {
                            ...ReviewInfo
                        }
                        price
                    }
                }
                fragment ProductInfo on Product {
                    id
                    name
                    description
                }
                fragment ReviewInfo on Review {
                    rating
                    text
                    author {
                        name
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));

            // Should have only the operation (fragments inlined)
            assertThat(result.getDefinitions()).hasSize(1);

            OperationDefinition op = getOperation(result);
            assertThat(op.getName()).isEqualTo("GetProducts");

            // Variables should be preserved
            assertThat(op.getVariableDefinitions()).hasSize(1);
            assertThat(op.getVariableDefinitions().get(0).getName()).isEqualTo("categoryId");
        }

        @Test
        @DisplayName("User profile with conditional fields")
        void userProfileWithConditionalFields() {
            String input = """
                query UserProfile($includePrivate: Boolean!) {
                    user {
                        id
                        name
                        email @include(if: $includePrivate)
                        phone @skip(if: false)
                        address @skip(if: true)
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));
            OperationDefinition op = getOperation(result);
            Field userField = (Field) op.getSelectionSet().getSelections().get(0);
            var selections = userField.getSelectionSet().getSelections();

            // address removed (@skip(if: true))
            // phone directive removed (@skip(if: false) evaluated)
            // email directive preserved (variable reference)
            // Fields sorted: email, id, name, phone

            List<String> fieldNames = selections.stream()
                .map(s -> ((Field) s).getName())
                .toList();

            assertThat(fieldNames).containsExactly("email", "id", "name", "phone");

            // email should still have @include directive
            Field emailField = (Field) selections.get(0);
            assertThat(emailField.getDirectives()).hasSize(1);
            assertThat(emailField.getDirectives().get(0).getName()).isEqualTo("include");

            // phone should have no directives
            Field phoneField = (Field) selections.get(3);
            assertThat(phoneField.getDirectives()).isEmpty();

            // SDL verification
            assertSdlEquals(result, "query UserProfile($includePrivate: Boolean!) { user { email @include(if: $includePrivate) id name phone } }");
        }

        @Test
        @DisplayName("Query with overlapping fragment selections")
        void overlappingFragmentSelections() {
            String input = """
                query {
                    user {
                        id
                        ...BasicInfo
                        ...ContactInfo
                    }
                }
                fragment BasicInfo on User {
                    id
                    name
                }
                fragment ContactInfo on User {
                    email
                    name
                }
                """;

            Document result = normalizer.normalize(parse(input));
            OperationDefinition op = getOperation(result);
            Field userField = (Field) op.getSelectionSet().getSelections().get(0);
            var selections = userField.getSelectionSet().getSelections();

            // After normalization:
            // - Both fragments inlined as ... on User, then simplified (user returns User)
            // - All fields merged and deduplicated at the same level
            // - Sorted: email, id, name
            assertThat(selections).hasSize(3);
            assertThat(((Field) selections.get(0)).getName()).isEqualTo("email");
            assertThat(((Field) selections.get(1)).getName()).isEqualTo("id");
            assertThat(((Field) selections.get(2)).getName()).isEqualTo("name");

            // SDL verification
            assertSdlEquals(result, "{ user { email id name } }");
        }
    }

    // ==================== ERROR HANDLING ====================

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("Throws on null document")
        void throwOnNullDocument() {
            assertThatThrownBy(() -> normalizer.normalize(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
        }

        @Test
        @DisplayName("Propagates circular fragment exception")
        void propagateCircularFragmentException() {
            String input = """
                query { user { ...A } }
                fragment A on User { ...B }
                fragment B on User { ...A }
                """;

            assertThatThrownBy(() -> normalizer.normalize(parse(input)))
                .isInstanceOf(OperationNormalizer.CircularFragmentException.class);
        }

        @Test
        @DisplayName("Propagates field conflict exception")
        void propagateFieldConflictException() {
            // This creates a conflict: same alias, different field names
            String input = """
                query { user { foo: name foo: email } }
                """;

            assertThatThrownBy(() -> normalizer.normalize(parse(input)))
                .isInstanceOf(OperationNormalizer.FieldConflictException.class);
        }
    }

    // ==================== STATIC FACTORY METHODS ====================

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("builder(schema) starts with all steps enabled")
        void builderDefaultsAllEnabled() {
            OperationNormalizer fromBuilder = OperationNormalizer.builder(TEST_SCHEMA).build();

            assertThat(fromBuilder.isInlineFragmentsEnabled()).isTrue();
            assertThat(fromBuilder.isProcessSkipIncludeEnabled()).isTrue();
            assertThat(fromBuilder.isDeduplicateFieldsEnabled()).isTrue();
            assertThat(fromBuilder.isSortSelectionsEnabled()).isTrue();
        }

        @Test
        @DisplayName("builder(null) throws IllegalArgumentException")
        void builderNullSchemaThrows() {
            assertThatThrownBy(() -> OperationNormalizer.builder(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
        }
    }

    // ==================== INLINE FRAGMENTS ====================

    @Nested
    @DisplayName("Inline Fragments")
    class InlineFragmentTests {

        @Test
        @DisplayName("Preserves inline fragment without type condition")
        void inlineFragmentWithoutTypeCondition() {
            String input = """
                {
                    user {
                        ... {
                            id
                            name
                        }
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));
            var selections = getSelections(result, "user");

            assertThat(selections).hasSize(1);
            assertThat(selections.get(0)).isInstanceOf(InlineFragment.class);
            InlineFragment fragment = (InlineFragment) selections.get(0);
            assertThat(fragment.getTypeCondition()).isNull();

            // SDL verification
            assertSdlEquals(result,"{user {... {id name}}}");
        }

        @Test
        @DisplayName("Preserves inline fragment with type condition")
        void inlineFragmentWithTypeCondition() {
            String input = """
                {
                    node {
                        ... on User {
                            id
                            name
                        }
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));
            var selections = getSelections(result, "node");

            assertThat(selections).hasSize(1);
            InlineFragment fragment = (InlineFragment) selections.get(0);
            assertThat(fragment.getTypeCondition().getName()).isEqualTo("User");

            // SDL verification
            assertSdlEquals(result,"{node {... on User {id name}}}");
        }

        @Test
        @DisplayName("Merges inline fragments with same type condition")
        void mergeInlineFragmentsWithSameType() {
            String input = """
                {
                    node {
                        ... on User {
                            id
                        }
                        ... on User {
                            name
                        }
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));
            var selections = getSelections(result, "node");

            // Should be merged into one inline fragment
            assertThat(selections).hasSize(1);
            InlineFragment fragment = (InlineFragment) selections.get(0);
            var fragmentSelections = fragment.getSelectionSet().getSelections();
            assertThat(fragmentSelections).hasSize(2);
            assertThat(((Field) fragmentSelections.get(0)).getName()).isEqualTo("id");
            assertThat(((Field) fragmentSelections.get(1)).getName()).isEqualTo("name");

            // SDL verification
            assertSdlEquals(result,"{node {... on User {id name}}}");
        }

        @Test
        @DisplayName("Keeps inline fragments with different type conditions separate")
        void separateInlineFragmentsWithDifferentTypes() {
            String input = """
                {
                    node {
                        ... on User {
                            username
                        }
                        ... on Post {
                            title
                        }
                        ... on Comment {
                            text
                        }
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));
            var selections = getSelections(result, "node");

            // Should have 3 separate inline fragments, sorted by type name
            assertThat(selections).hasSize(3);
            assertThat(((InlineFragment) selections.get(0)).getTypeCondition().getName()).isEqualTo("Comment");
            assertThat(((InlineFragment) selections.get(1)).getTypeCondition().getName()).isEqualTo("Post");
            assertThat(((InlineFragment) selections.get(2)).getTypeCondition().getName()).isEqualTo("User");

            // SDL verification
            String expectedSdl = "{node {... on Comment {text} ... on Post {title} ... on User {username}}}";
            assertSdlEquals(result,expectedSdl);
        }

        @Test
        @DisplayName("Handles nested inline fragments")
        void nestedInlineFragments() {
            String input = """
                {
                    search {
                        ... on SearchResult {
                            ... on User {
                                id
                            }
                            ... on Post {
                                title
                            }
                        }
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));
            var selections = getSelections(result, "search");

            assertThat(selections).hasSize(1);
            InlineFragment outerFragment = (InlineFragment) selections.get(0);
            assertThat(outerFragment.getTypeCondition().getName()).isEqualTo("SearchResult");

            var innerSelections = outerFragment.getSelectionSet().getSelections();
            assertThat(innerSelections).hasSize(2);
        }

        @Test
        @DisplayName("Inline fragment with directives")
        void inlineFragmentWithDirectives() {
            String input = """
                {
                    node {
                        ... on User @include(if: true) {
                            id
                        }
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));
            var selections = getSelections(result, "node");

            assertThat(selections).hasSize(1);
            InlineFragment fragment = (InlineFragment) selections.get(0);
            // @include(if: true) should be removed
            assertThat(fragment.getDirectives()).isEmpty();
        }

        @Test
        @DisplayName("Inline fragment with skip directive removes it")
        void inlineFragmentWithSkipTrue() {
            String input = """
                {
                    node {
                        id
                        ... on User @skip(if: true) {
                            name
                        }
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));
            var selections = getSelections(result, "node");

            // Only id field should remain
            assertThat(selections).hasSize(1);
            assertThat(selections.get(0)).isInstanceOf(Field.class);
            assertThat(((Field) selections.get(0)).getName()).isEqualTo("id");

            // SDL verification
            assertSdlEquals(result,"{node {id}}");
        }

        @Test
        @DisplayName("Fields come before inline fragments after sorting")
        void fieldsBeforeInlineFragments() {
            String input = """
                {
                    node {
                        ... on User { name }
                        id
                        ... on Post { title }
                        __typename
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));
            var selections = getSelections(result, "node");

            // Fields first (sorted), then inline fragments (sorted by type)
            assertThat(selections).hasSize(4);
            assertThat(selections.get(0)).isInstanceOf(Field.class);
            assertThat(((Field) selections.get(0)).getName()).isEqualTo("__typename");
            assertThat(selections.get(1)).isInstanceOf(Field.class);
            assertThat(((Field) selections.get(1)).getName()).isEqualTo("id");
            assertThat(selections.get(2)).isInstanceOf(InlineFragment.class);
            assertThat(selections.get(3)).isInstanceOf(InlineFragment.class);

            // SDL verification
            String expectedSdl = "{node {__typename id ... on Post {title} ... on User {name}}}";
            assertSdlEquals(result,expectedSdl);
        }

    }

    // ==================== TYPE CONDITION SIMPLIFICATION (SCHEMA-AWARE) ====================

    @Nested
    @DisplayName("Type Condition Simplification")
    class TypeConditionSimplificationTests {

        private OperationNormalizer schemaNormalizer;

        @BeforeEach
        void setUp() {
            // Build a test schema with Animal interface and Dog/Cat implementations
            String sdl = """
                type Query {
                    animals: [Animal]
                    dog: Dog
                    cat: Cat
                    node(id: ID!): Node
                }
                interface Animal {
                    id: ID!
                    animalId: ID!
                    name: String
                }
                type Dog implements Animal {
                    id: ID!
                    animalId: ID!
                    name: String
                    breed: String
                }
                type Cat implements Animal {
                    id: ID!
                    animalId: ID!
                    name: String
                    color: String
                }
                interface Node {
                    id: ID!
                }
                type Product implements Node {
                    id: ID!
                    name: String
                    price: Float
                }
                union SearchResult = Dog | Cat | Product
                """;

            graphql.schema.GraphQLSchema schema = graphql.schema.idl.SchemaGenerator.createdMockedSchema(sdl);
            schemaNormalizer = OperationNormalizer.builder(schema)
                .inlineFragments(true)
                .deduplicateFields(true)
                .sortSelections(true)
                .processSkipInclude(true)
                .build();
        }

        private void assertSimplifiedEquals(String input, String expected) {
            Document result = schemaNormalizer.normalize(parse(input));
            String actual = AstPrinter.printAstCompact(result);
            String expectedCompact = AstPrinter.printAstCompact(parse(expected));
            assertThat(actual).isEqualTo(expectedCompact);
        }

        @Test
        @DisplayName("Removes widening ... on Animal inside ... on Dog")
        void wideningInsideNarrowing() {
            assertSimplifiedEquals(
                "{ animals { ... on Dog { ... on Animal { animalId } } } }",
                "{ animals { ... on Dog { animalId } } }"
            );
        }

        @Test
        @DisplayName("Removes widening ... on Animal on concrete Dog field")
        void wideningOnConcreteTypeField() {
            assertSimplifiedEquals(
                "{ dog { ... on Animal { animalId } } }",
                "{ dog { animalId } }"
            );
        }

        @Test
        @DisplayName("Removes widening with own fields mixed in")
        void wideningWithOwnFields() {
            assertSimplifiedEquals(
                "{ animals { ... on Dog { breed ... on Animal { id name } } } }",
                "{ animals { ... on Dog { breed id name } } }"
            );
        }

        @Test
        @DisplayName("Removes widening from named fragment spread inside narrowing")
        void wideningViaNamedFragment() {
            String input = """
                {
                    animals {
                        ... on Dog {
                            breed
                            ...AnimalFields
                        }
                    }
                }
                fragment AnimalFields on Animal {
                    id
                    name
                }
                """;

            assertSimplifiedEquals(input, "{ animals { ... on Dog { breed id name } } }");
        }

        @Test
        @DisplayName("Removes widening from named fragment spread on concrete type field")
        void wideningViaNamedFragmentOnConcreteType() {
            String input = """
                {
                    dog {
                        breed
                        ...AnimalFields
                    }
                }
                fragment AnimalFields on Animal {
                    id
                    name
                }
                """;

            assertSimplifiedEquals(input, "{ dog { breed id name } }");
        }

        @Test
        @DisplayName("Keeps narrowing type condition (not redundant)")
        void narrowingNotRemoved() {
            // ... on Dog inside animals (Animal) is narrowing, should be kept
            assertSimplifiedEquals(
                "{ animals { ... on Dog { breed } } }",
                "{ animals { ... on Dog { breed } } }"
            );
        }

        @Test
        @DisplayName("Removes same-type condition")
        void sameTypeConditionRemoved() {
            assertSimplifiedEquals(
                "{ dog { ... on Dog { breed } } }",
                "{ dog { breed } }"
            );
        }

        @Test
        @DisplayName("Removes deeply nested redundant type conditions")
        void deeplyNestedWidening() {
            // Dog -> ... on Animal -> ... on Animal -> id  (double widening)
            assertSimplifiedEquals(
                "{ animals { ... on Dog { ... on Animal { ... on Animal { id } } } } }",
                "{ animals { ... on Dog { id } } }"
            );
        }

        @Test
        @DisplayName("Keeps sibling narrowing fragments while removing widening")
        void mixedNarrowingAndWidening() {
            // animals returns [Animal], ... on Dog narrows, inside Dog ... on Animal widens
            // ... on Cat is a separate narrowing — should be kept
            assertSimplifiedEquals(
                """
                {
                    animals {
                        ... on Dog {
                            breed
                            ... on Animal { id }
                        }
                        ... on Cat {
                            color
                        }
                    }
                }
                """,
                "{ animals { ... on Cat { color } ... on Dog { breed id } } }"
            );
        }
    }

    // ==================== UNION TYPES ====================

    @Nested
    @DisplayName("Union Types")
    class UnionTypeTests {

        @Test
        @DisplayName("Handles union type with multiple inline fragments")
        void unionTypeWithMultipleFragments() {
            String input = """
                {
                    searchResults {
                        ... on User {
                            id
                            username
                        }
                        ... on Post {
                            id
                            title
                            content
                        }
                        ... on Comment {
                            id
                            text
                        }
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));
            var selections = getSelections(result, "searchResults");

            assertThat(selections).hasSize(3);
            // Sorted by type name: Comment, Post, User
            assertThat(((InlineFragment) selections.get(0)).getTypeCondition().getName()).isEqualTo("Comment");
            assertThat(((InlineFragment) selections.get(1)).getTypeCondition().getName()).isEqualTo("Post");
            assertThat(((InlineFragment) selections.get(2)).getTypeCondition().getName()).isEqualTo("User");

            // SDL verification
            String expectedSdl = "{searchResults {... on Comment {id text} ... on Post {content id title} ... on User {id username}}}";
            assertSdlEquals(result,expectedSdl);
        }

        @Test
        @DisplayName("Handles union with __typename")
        void unionWithTypename() {
            String input = """
                {
                    searchResults {
                        __typename
                        ... on User { id }
                        ... on Post { id }
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));
            var selections = getSelections(result, "searchResults");

            // __typename field first, then inline fragments
            assertThat(selections).hasSize(3);
            assertThat(((Field) selections.get(0)).getName()).isEqualTo("__typename");

            // SDL verification
            String expectedSdl = "{searchResults {__typename ... on Post {id} ... on User {id}}}";
            assertSdlEquals(result,expectedSdl);
        }

        @Test
        @DisplayName("Handles union with named fragments")
        void unionWithNamedFragments() {
            String input = """
                {
                    searchResults {
                        ...UserFragment
                        ...PostFragment
                    }
                }
                fragment UserFragment on User {
                    id
                    username
                }
                fragment PostFragment on Post {
                    id
                    title
                }
                """;

            Document result = normalizer.normalize(parse(input));
            assertThat(result.getDefinitions()).hasSize(1); // Only operation, fragments inlined

            var selections = getSelections(result, "searchResults");
            assertThat(selections).hasSize(2);

            // SDL verification
            String expectedSdl = "{searchResults {... on Post {id title} ... on User {id username}}}";
            assertSdlEquals(result,expectedSdl);
        }
    }

    // ==================== INTERFACE TYPES ====================

    @Nested
    @DisplayName("Interface Types")
    class InterfaceTypeTests {

        @Test
        @DisplayName("Handles interface with common and specific fields")
        void interfaceWithCommonAndSpecificFields() {
            String input = """
                {
                    nodes {
                        id
                        ... on User {
                            username
                            email
                        }
                        ... on Post {
                            title
                            body
                        }
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));
            var selections = getSelections(result, "nodes");

            // id field first, then inline fragments
            assertThat(selections).hasSize(3);
            assertThat(((Field) selections.get(0)).getName()).isEqualTo("id");
            assertThat(selections.get(1)).isInstanceOf(InlineFragment.class);
            assertThat(selections.get(2)).isInstanceOf(InlineFragment.class);

            // SDL verification
            String expectedSdl = "{nodes {id ... on Post {body title} ... on User {email username}}}";
            assertSdlEquals(result,expectedSdl);
        }

        @Test
        @DisplayName("Fragment on interface type")
        void fragmentOnInterfaceType() {
            String input = """
                {
                    nodes {
                        ...NodeFields
                    }
                }
                fragment NodeFields on Node {
                    id
                    createdAt
                }
                """;

            Document result = normalizer.normalize(parse(input));
            var selections = getSelections(result, "nodes");

            assertThat(selections).hasSize(1);
            InlineFragment fragment = (InlineFragment) selections.get(0);
            assertThat(fragment.getTypeCondition().getName()).isEqualTo("Node");

            var fragmentSelections = fragment.getSelectionSet().getSelections();
            assertThat(fragmentSelections).hasSize(2);
            assertThat(((Field) fragmentSelections.get(0)).getName()).isEqualTo("createdAt");
            assertThat(((Field) fragmentSelections.get(1)).getName()).isEqualTo("id");

            // SDL verification
            String expectedSdl = "{nodes {... on Node {createdAt id}}}";
            assertSdlEquals(result,expectedSdl);
        }

        @Test
        @DisplayName("Multiple interfaces implementation")
        void multipleInterfacesImplementation() {
            String input = """
                {
                    items {
                        ... on Timestamped {
                            createdAt
                            updatedAt
                        }
                        ... on Authored {
                            author { name }
                        }
                        ... on Post {
                            title
                        }
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));
            var selections = getSelections(result, "items");

            assertThat(selections).hasSize(3);
            // Sorted: Authored, Post, Timestamped
            assertThat(((InlineFragment) selections.get(0)).getTypeCondition().getName()).isEqualTo("Authored");
            assertThat(((InlineFragment) selections.get(1)).getTypeCondition().getName()).isEqualTo("Post");
            assertThat(((InlineFragment) selections.get(2)).getTypeCondition().getName()).isEqualTo("Timestamped");

            // SDL verification
            String expectedSdl = "{items {... on Authored {author {name}} ... on Post {title} ... on Timestamped {createdAt updatedAt}}}";
            assertSdlEquals(result,expectedSdl);
        }
    }

    // ==================== VARIABLES ====================

    @Nested
    @DisplayName("Variables")
    class VariableTests {

        @Test
        @DisplayName("Preserves variables in field arguments")
        void variablesInFieldArguments() {
            String input = """
                query GetUser($id: ID!, $includeDeleted: Boolean) {
                    user(id: $id, includeDeleted: $includeDeleted) {
                        id
                        name
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));
            OperationDefinition op = getOperation(result);

            // Variable definitions preserved and sorted
            assertThat(op.getVariableDefinitions()).hasSize(2);
            assertThat(op.getVariableDefinitions().get(0).getName()).isEqualTo("id");
            assertThat(op.getVariableDefinitions().get(1).getName()).isEqualTo("includeDeleted");

            // Arguments preserved
            Field userField = (Field) op.getSelectionSet().getSelections().get(0);
            assertThat(userField.getArguments()).hasSize(2);

            // SDL verification
            String expectedSdl = "query GetUser($id: ID!, $includeDeleted: Boolean) {user(id: $id, includeDeleted: $includeDeleted) {id name}}";
            assertSdlEquals(result,expectedSdl);
        }

        @Test
        @DisplayName("Preserves variables in @skip directive")
        void variablesInSkipDirective() {
            String input = """
                query GetUser($skipEmail: Boolean!) {
                    user {
                        id
                        email @skip(if: $skipEmail)
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));
            var selections = getSelections(result, "user");

            // email field should remain with directive
            assertThat(selections).hasSize(2);
            Field emailField = (Field) selections.get(0); // sorted: email, id
            assertThat(emailField.getName()).isEqualTo("email");
            assertThat(emailField.getDirectives()).hasSize(1);
            assertThat(emailField.getDirectives().get(0).getName()).isEqualTo("skip");

            // SDL verification
            String expectedSdl = "query GetUser($skipEmail: Boolean!) {user {email @skip(if: $skipEmail) id}}";
            assertSdlEquals(result,expectedSdl);
        }

        @Test
        @DisplayName("Preserves variables in @include directive")
        void variablesInIncludeDirective() {
            String input = """
                query GetUser($includeProfile: Boolean!) {
                    user {
                        id
                        profile @include(if: $includeProfile) {
                            bio
                        }
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));
            var selections = getSelections(result, "user");

            assertThat(selections).hasSize(2);
            Field profileField = (Field) selections.get(1); // sorted: id, profile
            assertThat(profileField.getName()).isEqualTo("profile");
            assertThat(profileField.getDirectives()).hasSize(1);

            // SDL verification
            String expectedSdl = "query GetUser($includeProfile: Boolean!) {user {id profile @include(if: $includeProfile) {bio}}}";
            assertSdlEquals(result,expectedSdl);
        }

        @Test
        @DisplayName("Handles complex variable types")
        void complexVariableTypes() {
            String input = """
                query Search(
                    $query: String!,
                    $filters: [FilterInput!]!,
                    $pagination: PaginationInput,
                    $sort: SortOrder = DESC
                ) {
                    search(
                        query: $query,
                        filters: $filters,
                        pagination: $pagination,
                        sort: $sort
                    ) {
                        results { id }
                        total
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));
            OperationDefinition op = getOperation(result);

            // Variable definitions sorted: filters, pagination, query, sort
            List<VariableDefinition> vars = op.getVariableDefinitions();
            assertThat(vars).hasSize(4);
            assertThat(vars.get(0).getName()).isEqualTo("filters");
            assertThat(vars.get(1).getName()).isEqualTo("pagination");
            assertThat(vars.get(2).getName()).isEqualTo("query");
            assertThat(vars.get(3).getName()).isEqualTo("sort");

            // Default value preserved
            assertThat(vars.get(3).getDefaultValue()).isNotNull();
        }

        @Test
        @DisplayName("Variables in nested arguments")
        void variablesInNestedArguments() {
            String input = """
                query GetPosts($authorId: ID!, $limit: Int!) {
                    posts(filter: { authorId: $authorId }, limit: $limit) {
                        id
                        title
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));
            OperationDefinition op = getOperation(result);
            Field postsField = (Field) op.getSelectionSet().getSelections().get(0);

            // Arguments sorted: filter, limit
            assertThat(postsField.getArguments()).hasSize(2);
            assertThat(postsField.getArguments().get(0).getName()).isEqualTo("filter");
            assertThat(postsField.getArguments().get(1).getName()).isEqualTo("limit");
        }
    }

    // ==================== ARGUMENT TYPES ====================

    @Nested
    @DisplayName("Argument Types")
    class ArgumentTypeTests {

        @Test
        @DisplayName("Handles all scalar argument types")
        void allScalarArgumentTypes() {
            String input = """
                {
                    testScalars(
                        stringArg: "hello",
                        intArg: 42,
                        floatArg: 3.14,
                        boolArg: true,
                        nullArg: null
                    ) {
                        result
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));
            OperationDefinition op = getOperation(result);
            Field testField = (Field) op.getSelectionSet().getSelections().get(0);

            // Arguments sorted alphabetically
            assertThat(testField.getArguments()).hasSize(5);
            assertThat(testField.getArguments().get(0).getName()).isEqualTo("boolArg");
            assertThat(testField.getArguments().get(1).getName()).isEqualTo("floatArg");
            assertThat(testField.getArguments().get(2).getName()).isEqualTo("intArg");
            assertThat(testField.getArguments().get(3).getName()).isEqualTo("nullArg");
            assertThat(testField.getArguments().get(4).getName()).isEqualTo("stringArg");
        }

        @Test
        @DisplayName("Handles enum arguments")
        void enumArguments() {
            String input = """
                {
                    posts(status: PUBLISHED, sort: { field: CREATED_AT, order: DESC }) {
                        id
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));
            OperationDefinition op = getOperation(result);
            Field postsField = (Field) op.getSelectionSet().getSelections().get(0);

            assertThat(postsField.getArguments()).hasSize(2);
            assertThat(postsField.getArguments().get(0).getName()).isEqualTo("sort");
            assertThat(postsField.getArguments().get(1).getName()).isEqualTo("status");
        }

        @Test
        @DisplayName("Handles list arguments")
        void listArguments() {
            String input = """
                {
                    users(ids: ["1", "2", "3"], roles: [ADMIN, USER]) {
                        id
                        name
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));
            OperationDefinition op = getOperation(result);
            Field usersField = (Field) op.getSelectionSet().getSelections().get(0);

            assertThat(usersField.getArguments()).hasSize(2);
            assertThat(usersField.getArguments().get(0).getName()).isEqualTo("ids");
            assertThat(usersField.getArguments().get(1).getName()).isEqualTo("roles");
        }

        @Test
        @DisplayName("Handles input object arguments")
        void inputObjectArguments() {
            String input = """
                mutation CreateUser($input: CreateUserInput!) {
                    createUser(input: {
                        name: "John",
                        email: "john@example.com",
                        profile: {
                            bio: "Hello",
                            avatar: "url"
                        }
                    }) {
                        id
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));
            OperationDefinition op = getOperation(result);
            Field createUserField = (Field) op.getSelectionSet().getSelections().get(0);

            assertThat(createUserField.getArguments()).hasSize(1);
            assertThat(createUserField.getArguments().get(0).getName()).isEqualTo("input");
        }
    }

    // ==================== ALIASES ====================

    @Nested
    @DisplayName("Aliases")
    class AliasTests {

        @Test
        @DisplayName("Preserves field aliases")
        void preservesFieldAliases() {
            String input = """
                {
                    user {
                        firstName: name
                        userEmail: email
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));
            var selections = getSelections(result, "user");

            assertThat(selections).hasSize(2);
            // Sorted by alias: firstName, userEmail
            Field firstNameField = (Field) selections.get(0);
            assertThat(firstNameField.getAlias()).isEqualTo("firstName");
            assertThat(firstNameField.getName()).isEqualTo("name");

            Field userEmailField = (Field) selections.get(1);
            assertThat(userEmailField.getAlias()).isEqualTo("userEmail");
            assertThat(userEmailField.getName()).isEqualTo("email");

            // SDL verification
            assertSdlEquals(result,"{user {firstName: name userEmail: email}}");
        }

        @Test
        @DisplayName("Same field with different aliases kept separate")
        void sameFieldDifferentAliasesKeptSeparate() {
            String input = """
                {
                    user(id: "1") {
                        first: posts(limit: 1) { id }
                        recent: posts(limit: 10) { id }
                        all: posts { id }
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));
            var selections = getSelections(result, "user");

            // All three should be kept separate, sorted by alias
            assertThat(selections).hasSize(3);
            assertThat(((Field) selections.get(0)).getAlias()).isEqualTo("all");
            assertThat(((Field) selections.get(1)).getAlias()).isEqualTo("first");
            assertThat(((Field) selections.get(2)).getAlias()).isEqualTo("recent");
        }

        @Test
        @DisplayName("Duplicate aliases with same field merge")
        void duplicateAliasesSameFieldMerge() {
            String input = """
                {
                    user {
                        info: profile { bio }
                        info: profile { avatar }
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));
            var selections = getSelections(result, "user");

            assertThat(selections).hasSize(1);
            Field infoField = (Field) selections.get(0);
            assertThat(infoField.getAlias()).isEqualTo("info");

            var profileSelections = infoField.getSelectionSet().getSelections();
            assertThat(profileSelections).hasSize(2);
            assertThat(((Field) profileSelections.get(0)).getName()).isEqualTo("avatar");
            assertThat(((Field) profileSelections.get(1)).getName()).isEqualTo("bio");

            // SDL verification
            assertSdlEquals(result,"{user {info: profile {avatar bio}}}");
        }

        @Test
        @DisplayName("Alias conflicts with different fields throw error")
        void aliasConflictsWithDifferentFields() {
            String input = """
                {
                    user {
                        info: name
                        info: email
                    }
                }
                """;

            assertThatThrownBy(() -> normalizer.normalize(parse(input)))
                .isInstanceOf(OperationNormalizer.FieldConflictException.class)
                .hasMessageContaining("info");
        }
    }

    // ==================== DEEPLY NESTED STRUCTURES ====================

    @Nested
    @DisplayName("Deeply Nested Structures")
    class DeeplyNestedTests {

        @Test
        @DisplayName("Handles deeply nested selections")
        void deeplyNestedSelections() {
            String input = """
                {
                    organization {
                        name
                        teams {
                            name
                            members {
                                name
                                profile {
                                    bio
                                    settings {
                                        theme
                                        notifications {
                                            email
                                            push
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));

            // Navigate to deepest level
            var orgSelections = getSelections(result, "organization");
            assertThat(orgSelections).hasSize(2);

            var teamSelections = getSelections(result, "organization", "teams");
            assertThat(teamSelections).hasSize(2);

            var memberSelections = getSelections(result, "organization", "teams", "members");
            assertThat(memberSelections).hasSize(2);

            var profileSelections = getSelections(result, "organization", "teams", "members", "profile");
            assertThat(profileSelections).hasSize(2);

            var settingsSelections = getSelections(result, "organization", "teams", "members", "profile", "settings");
            assertThat(settingsSelections).hasSize(2);

            var notificationSelections = getSelections(result, "organization", "teams", "members", "profile", "settings", "notifications");
            assertThat(notificationSelections).hasSize(2);
            assertThat(((Field) notificationSelections.get(0)).getName()).isEqualTo("email");
            assertThat(((Field) notificationSelections.get(1)).getName()).isEqualTo("push");
        }

        @Test
        @DisplayName("Handles deeply nested fragments")
        void deeplyNestedFragments() {
            String input = """
                {
                    user {
                        ...L1
                    }
                }
                fragment L1 on User {
                    profile {
                        ...L2
                    }
                }
                fragment L2 on Profile {
                    settings {
                        ...L3
                    }
                }
                fragment L3 on Settings {
                    preferences {
                        ...L4
                    }
                }
                fragment L4 on Preferences {
                    theme
                    language
                }
                """;

            Document result = normalizer.normalize(parse(input));
            assertThat(result.getDefinitions()).hasSize(1);

            // All fragments inlined; ... on User simplified (user returns User)
            // Inner type conditions (Profile, Settings, Preferences) preserved (unknown to schema)
            var userSelections = getSelections(result, "user");
            assertThat(userSelections).hasSize(1);
            assertThat(userSelections.get(0)).isInstanceOf(Field.class);
            assertThat(((Field) userSelections.get(0)).getName()).isEqualTo("profile");
        }
    }

    // ==================== MUTATIONS AND SUBSCRIPTIONS ====================

    @Nested
    @DisplayName("Mutations and Subscriptions")
    class MutationSubscriptionTests {

        @Test
        @DisplayName("Normalizes mutation operations")
        void normalizeMutation() {
            String input = """
                mutation CreatePost($input: CreatePostInput!) {
                    createPost(input: $input) {
                        id
                        title
                        author {
                            id
                            name
                        }
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));
            OperationDefinition op = getOperation(result);

            assertThat(op.getOperation()).isEqualTo(OperationDefinition.Operation.MUTATION);
            assertThat(op.getName()).isEqualTo("CreatePost");

            var postSelections = getSelections(result, "createPost");
            assertThat(postSelections).hasSize(3);
            assertThat(((Field) postSelections.get(0)).getName()).isEqualTo("author");
            assertThat(((Field) postSelections.get(1)).getName()).isEqualTo("id");
            assertThat(((Field) postSelections.get(2)).getName()).isEqualTo("title");

            // SDL verification
            String expectedSdl = "mutation CreatePost($input: CreatePostInput!) {createPost(input: $input) {author {id name} id title}}";
            assertSdlEquals(result,expectedSdl);
        }

        @Test
        @DisplayName("Normalizes subscription operations")
        void normalizeSubscription() {
            String input = """
                subscription OnMessageReceived($roomId: ID!) {
                    messageReceived(roomId: $roomId) {
                        id
                        text
                        sender {
                            id
                            name
                        }
                        createdAt
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));
            OperationDefinition op = getOperation(result);

            assertThat(op.getOperation()).isEqualTo(OperationDefinition.Operation.SUBSCRIPTION);
            assertThat(op.getName()).isEqualTo("OnMessageReceived");

            var messageSelections = getSelections(result, "messageReceived");
            assertThat(messageSelections).hasSize(4);
            assertThat(((Field) messageSelections.get(0)).getName()).isEqualTo("createdAt");
            assertThat(((Field) messageSelections.get(1)).getName()).isEqualTo("id");
            assertThat(((Field) messageSelections.get(2)).getName()).isEqualTo("sender");
            assertThat(((Field) messageSelections.get(3)).getName()).isEqualTo("text");

            // SDL verification
            String expectedSdl = "subscription OnMessageReceived($roomId: ID!) {messageReceived(roomId: $roomId) {createdAt id sender {id name} text}}";
            assertSdlEquals(result,expectedSdl);
        }

        @Test
        @DisplayName("Mutation with fragments")
        void mutationWithFragments() {
            String input = """
                mutation UpdateUser($id: ID!, $input: UpdateUserInput!) {
                    updateUser(id: $id, input: $input) {
                        ...UserFields
                    }
                }
                fragment UserFields on User {
                    id
                    name
                    email
                }
                """;

            Document result = normalizer.normalize(parse(input));
            assertThat(result.getDefinitions()).hasSize(1);

            var userSelections = getSelections(result, "updateUser");
            assertThat(userSelections).hasSize(1);
            assertThat(userSelections.get(0)).isInstanceOf(InlineFragment.class);

            // SDL verification
            String expectedSdl = "mutation UpdateUser($id: ID!, $input: UpdateUserInput!) {updateUser(id: $id, input: $input) {... on User {email id name}}}";
            assertSdlEquals(result,expectedSdl);
        }
    }

    // ==================== MULTIPLE OPERATIONS ====================

    @Nested
    @DisplayName("Multiple Operations")
    class MultipleOperationTests {

        @Test
        @DisplayName("Normalizes document with multiple operations")
        void multipleOperations() {
            String input = """
                query GetUser($id: ID!) {
                    user(id: $id) { id name }
                }
                query GetPosts {
                    posts { id title }
                }
                mutation CreateUser($input: CreateUserInput!) {
                    createUser(input: $input) { id }
                }
                """;

            Document result = normalizer.normalize(parse(input));

            // All operations should be normalized
            assertThat(result.getDefinitions()).hasSize(3);

            OperationDefinition getUser = getOperation(result, "GetUser");
            assertThat(getUser.getOperation()).isEqualTo(OperationDefinition.Operation.QUERY);

            OperationDefinition getPosts = getOperation(result, "GetPosts");
            assertThat(getPosts.getOperation()).isEqualTo(OperationDefinition.Operation.QUERY);

            OperationDefinition createUser = getOperation(result, "CreateUser");
            assertThat(createUser.getOperation()).isEqualTo(OperationDefinition.Operation.MUTATION);
        }

        @Test
        @DisplayName("Multiple operations sharing fragments")
        void multipleOperationsSharingFragments() {
            String input = """
                query GetUser {
                    user { ...UserFields }
                }
                query GetCurrentUser {
                    currentUser { ...UserFields }
                }
                fragment UserFields on User {
                    id
                    name
                    email
                }
                """;

            Document result = normalizer.normalize(parse(input));
            assertThat(result.getDefinitions()).hasSize(2);

            // GetUser: ... on User simplified (user returns User), fields at same level
            OperationDefinition getUser = getOperation(result, "GetUser");
            Field userField = (Field) getUser.getSelectionSet().getSelections().get(0);
            assertThat(userField.getSelectionSet().getSelections().get(0)).isInstanceOf(Field.class);

            // GetCurrentUser: ... on User preserved (currentUser unknown to schema)
            OperationDefinition getCurrentUser = getOperation(result, "GetCurrentUser");
            Field currentUserField = (Field) getCurrentUser.getSelectionSet().getSelections().get(0);
            assertThat(currentUserField.getSelectionSet().getSelections().get(0)).isInstanceOf(InlineFragment.class);
        }
    }

    // ==================== __typename HANDLING ====================

    @Nested
    @DisplayName("__typename Handling")
    class TypenameTests {

        @Test
        @DisplayName("Preserves __typename field")
        void preservesTypename() {
            String input = """
                {
                    user {
                        __typename
                        id
                        name
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));
            var selections = getSelections(result, "user");

            assertThat(selections).hasSize(3);
            // __typename sorts first alphabetically
            assertThat(((Field) selections.get(0)).getName()).isEqualTo("__typename");
            assertThat(((Field) selections.get(1)).getName()).isEqualTo("id");
            assertThat(((Field) selections.get(2)).getName()).isEqualTo("name");

            // SDL verification
            assertSdlEquals(result,"{user {__typename id name}}");
        }

        @Test
        @DisplayName("Deduplicates __typename")
        void deduplicatesTypename() {
            String input = """
                {
                    user {
                        __typename
                        id
                        __typename
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));
            var selections = getSelections(result, "user");

            assertThat(selections).hasSize(2);
            assertThat(((Field) selections.get(0)).getName()).isEqualTo("__typename");
            assertThat(((Field) selections.get(1)).getName()).isEqualTo("id");

            // SDL verification
            assertSdlEquals(result,"{user {__typename id}}");
        }

        @Test
        @DisplayName("__typename in inline fragments")
        void typenameInInlineFragments() {
            String input = """
                {
                    node {
                        __typename
                        ... on User {
                            __typename
                            id
                        }
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));
            var selections = getSelections(result, "node");

            assertThat(selections).hasSize(2);
            assertThat(((Field) selections.get(0)).getName()).isEqualTo("__typename");

            InlineFragment fragment = (InlineFragment) selections.get(1);
            var fragmentSelections = fragment.getSelectionSet().getSelections();
            assertThat(fragmentSelections).hasSize(2);
            assertThat(((Field) fragmentSelections.get(0)).getName()).isEqualTo("__typename");
            assertThat(((Field) fragmentSelections.get(1)).getName()).isEqualTo("id");

            // SDL verification
            assertSdlEquals(result,"{node {__typename ... on User {__typename id}}}");
        }
    }

    // ==================== EDGE CASES ====================

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Empty selection set after directive processing")
        void emptySelectionSetAfterDirectives() {
            String input = """
                {
                    user {
                        id @skip(if: true)
                        name @skip(if: true)
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));
            var selections = getSelections(result, "user");

            // All fields removed
            assertThat(selections).isEmpty();

            // SDL verification
            assertSdlEquals(result,"{user}");
        }

        @Test
        @DisplayName("Handles leaf field without selection set")
        void leafFieldWithoutSelectionSet() {
            String input = "{ user { id } }";

            Document result = normalizer.normalize(parse(input));
            var selections = getSelections(result, "user");

            assertThat(selections).hasSize(1);
            Field idField = (Field) selections.get(0);
            assertThat(idField.getName()).isEqualTo("id");
            assertThat(idField.getSelectionSet()).isNull();

            // SDL verification
            assertSdlEquals(result,"{user {id}}");
        }

        @Test
        @DisplayName("Handles multiple root fields")
        void multipleRootFields() {
            String input = """
                {
                    user { id }
                    posts { id }
                    comments { id }
                }
                """;

            Document result = normalizer.normalize(parse(input));
            OperationDefinition op = getOperation(result);
            var rootSelections = op.getSelectionSet().getSelections();

            // Sorted: comments, posts, user
            assertThat(rootSelections).hasSize(3);
            assertThat(((Field) rootSelections.get(0)).getName()).isEqualTo("comments");
            assertThat(((Field) rootSelections.get(1)).getName()).isEqualTo("posts");
            assertThat(((Field) rootSelections.get(2)).getName()).isEqualTo("user");

            // SDL verification
            assertSdlEquals(result,"{comments {id} posts {id} user {id}}");
        }

        @Test
        @DisplayName("Handles operation with only fragments")
        void operationWithOnlyFragments() {
            String input = """
                {
                    user {
                        ...A
                        ...B
                    }
                }
                fragment A on User { id }
                fragment B on User { name }
                """;

            Document result = normalizer.normalize(parse(input));
            var selections = getSelections(result, "user");

            // Both fragments inlined as ... on User, then simplified (user returns User)
            // Fields merged at same level
            assertThat(selections).hasSize(2);
            assertThat(((Field) selections.get(0)).getName()).isEqualTo("id");
            assertThat(((Field) selections.get(1)).getName()).isEqualTo("name");

            // SDL verification
            assertSdlEquals(result,"{user {id name}}");
        }

        @Test
        @DisplayName("Handles special characters in string arguments")
        void specialCharactersInStringArguments() {
            String input = """
                {
                    search(query: "hello \\"world\\" \\n test") {
                        id
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));
            OperationDefinition op = getOperation(result);
            Field searchField = (Field) op.getSelectionSet().getSelections().get(0);

            assertThat(searchField.getArguments()).hasSize(1);
            assertThat(searchField.getArguments().get(0).getName()).isEqualTo("query");
        }

        @Test
        @DisplayName("Handles negative numbers in arguments")
        void negativeNumbersInArguments() {
            String input = """
                {
                    range(min: -100, max: 100) {
                        value
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));
            OperationDefinition op = getOperation(result);
            Field rangeField = (Field) op.getSelectionSet().getSelections().get(0);

            assertThat(rangeField.getArguments()).hasSize(2);
            assertThat(rangeField.getArguments().get(0).getName()).isEqualTo("max");
            assertThat(rangeField.getArguments().get(1).getName()).isEqualTo("min");
        }

        @Test
        @DisplayName("Handles empty list arguments")
        void emptyListArguments() {
            String input = """
                {
                    users(ids: []) {
                        id
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));
            OperationDefinition op = getOperation(result);
            Field usersField = (Field) op.getSelectionSet().getSelections().get(0);

            assertThat(usersField.getArguments()).hasSize(1);

            // SDL verification
            assertSdlEquals(result,"{users(ids: []) {id}}");
        }

        @Test
        @DisplayName("Handles empty input object arguments")
        void emptyInputObjectArguments() {
            String input = """
                {
                    users(filter: {}) {
                        id
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));
            OperationDefinition op = getOperation(result);
            Field usersField = (Field) op.getSelectionSet().getSelections().get(0);

            assertThat(usersField.getArguments()).hasSize(1);

            // SDL verification
            assertSdlEquals(result,"{users(filter: {}) {id}}");
        }
    }

    // ==================== DIRECTIVE COMBINATIONS ====================

    @Nested
    @DisplayName("Directive Combinations")
    class DirectiveCombinationTests {

        @Test
        @DisplayName("Multiple skip directives")
        void multipleSkipDirectives() {
            String input = """
                query Test($a: Boolean!, $b: Boolean!) {
                    user {
                        id @skip(if: $a) @skip(if: $b)
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));
            var selections = getSelections(result, "user");

            assertThat(selections).hasSize(1);
            Field idField = (Field) selections.get(0);
            // Both variable directives preserved
            assertThat(idField.getDirectives()).hasSize(2);
        }

        @Test
        @DisplayName("Skip and include on same field")
        void skipAndIncludeOnSameField() {
            String input = """
                query Test($skip: Boolean!, $include: Boolean!) {
                    user {
                        id @skip(if: $skip) @include(if: $include)
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));
            var selections = getSelections(result, "user");

            assertThat(selections).hasSize(1);
            Field idField = (Field) selections.get(0);
            assertThat(idField.getDirectives()).hasSize(2);

            // SDL verification
            String expectedSdl = "query Test($include: Boolean!, $skip: Boolean!) {user {id @include(if: $include) @skip(if: $skip)}}";
            assertSdlEquals(result,expectedSdl);
        }

        @Test
        @DisplayName("Custom directives preserved")
        void customDirectivesPreserved() {
            String input = """
                {
                    user {
                        id @deprecated(reason: "Use newId")
                        name @uppercase
                        email @auth(requires: ADMIN) @cache(maxAge: 3600)
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));
            var selections = getSelections(result, "user");

            assertThat(selections).hasSize(3);

            Field emailField = (Field) selections.get(0);
            assertThat(emailField.getName()).isEqualTo("email");
            assertThat(emailField.getDirectives()).hasSize(2);
            assertThat(emailField.getDirectives().get(0).getName()).isEqualTo("auth");
            assertThat(emailField.getDirectives().get(1).getName()).isEqualTo("cache");

            Field idField = (Field) selections.get(1);
            assertThat(idField.getName()).isEqualTo("id");
            assertThat(idField.getDirectives()).hasSize(1);
            assertThat(idField.getDirectives().get(0).getName()).isEqualTo("deprecated");

            Field nameField = (Field) selections.get(2);
            assertThat(nameField.getName()).isEqualTo("name");
            assertThat(nameField.getDirectives()).hasSize(1);
            assertThat(nameField.getDirectives().get(0).getName()).isEqualTo("uppercase");
        }

        @Test
        @DisplayName("Directives on inline fragments with variables")
        void directivesOnInlineFragmentsWithVariables() {
            String input = """
                query Test($showAdmin: Boolean!) {
                    node {
                        ... on User @include(if: $showAdmin) {
                            adminField
                        }
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));
            var selections = getSelections(result, "node");

            assertThat(selections).hasSize(1);
            InlineFragment fragment = (InlineFragment) selections.get(0);
            assertThat(fragment.getDirectives()).hasSize(1);
            assertThat(fragment.getDirectives().get(0).getName()).isEqualTo("include");

            // SDL verification
            String expectedSdl = "query Test($showAdmin: Boolean!) {node {... on User @include(if: $showAdmin) {adminField}}}";
            assertSdlEquals(result,expectedSdl);
        }
    }

    // ==================== CONSISTENT OUTPUT ====================

    @Nested
    @DisplayName("Consistent Output")
    class ConsistentOutputTests {

        @Test
        @DisplayName("Same query different formatting produces identical output")
        void sameQueryDifferentFormatting() {
            String input1 = "{user{id name email}}";
            String input2 = """
                {
                    user {
                        id
                        name
                        email
                    }
                }
                """;
            String input3 = "{ user { id, name, email } }";

            Document result1 = normalizer.normalize(parse(input1));
            Document result2 = normalizer.normalize(parse(input2));
            Document result3 = normalizer.normalize(parse(input3));

            String output1 = AstPrinter.printAstCompact(result1);
            String output2 = AstPrinter.printAstCompact(result2);
            String output3 = AstPrinter.printAstCompact(result3);

            assertThat(output1).isEqualTo(output2).isEqualTo(output3);
            assertSdlEquals(result1, "{ user { email id name } }");
        }

        @Test
        @DisplayName("Equivalent queries with different fragment usage produce identical output")
        void equivalentQueriesWithFragments() {
            String withFragment = """
                {
                    user {
                        ...UserFields
                    }
                }
                fragment UserFields on User {
                    id
                    name
                }
                """;

            String withInlineFragment = """
                {
                    user {
                        ... on User {
                            id
                            name
                        }
                    }
                }
                """;

            Document result1 = normalizer.normalize(parse(withFragment));
            Document result2 = normalizer.normalize(parse(withInlineFragment));

            String output1 = AstPrinter.printAstCompact(result1);
            String output2 = AstPrinter.printAstCompact(result2);

            assertThat(output1).isEqualTo(output2);
            assertSdlEquals(result1, "{ user { id name } }");
        }

        @Test
        @DisplayName("Queries with different field ordering produce identical output")
        void differentFieldOrdering() {
            String input1 = "{ user { z y x w v u t s r q p o n m l k j i h g f e d c b a } }";
            String input2 = "{ user { a b c d e f g h i j k l m n o p q r s t u v w x y z } }";
            String input3 = "{ user { m n o p q r s t u v w x y z a b c d e f g h i j k l } }";

            Document result1 = normalizer.normalize(parse(input1));
            Document result2 = normalizer.normalize(parse(input2));
            Document result3 = normalizer.normalize(parse(input3));

            String output1 = AstPrinter.printAstCompact(result1);
            String output2 = AstPrinter.printAstCompact(result2);
            String output3 = AstPrinter.printAstCompact(result3);

            assertThat(output1).isEqualTo(output2).isEqualTo(output3);
            assertSdlEquals(result1, "{ user { a b c d e f g h i j k l m n o p q r s t u v w x y z } }");
        }
    }

    // ==================== SIMPLE SDL VERIFICATION TESTS ====================

    @Nested
    @DisplayName("Simple SDL Verification")
    class SimpleSdlVerificationTests {

        @Test
        @DisplayName("Simple field selection")
        void simpleFieldSelection() {
            assertNormalizedEquals(
                "{ user { name id email } }",
                "{ user { email id name } }"
            );
        }

        @Test
        @DisplayName("Field deduplication")
        void fieldDeduplication() {
            assertNormalizedEquals(
                "{ user { id name id } }",
                "{ user { id name } }"
            );
        }

        @Test
        @DisplayName("Skip directive removes field")
        void skipDirectiveRemovesField() {
            assertNormalizedEquals(
                "{ user { id @skip(if: true) name } }",
                "{ user { name } }"
            );
        }

        @Test
        @DisplayName("Include directive removes field")
        void includeDirectiveRemovesField() {
            assertNormalizedEquals(
                "{ user { id @include(if: false) name } }",
                "{ user { name } }"
            );
        }

        @Test
        @DisplayName("Fragment inlining")
        void fragmentInlining() {
            assertNormalizedEquals(
                """
                { user { ...F } }
                fragment F on User { id name }
                """,
                "{ user { id name } }"
            );
        }

        @Test
        @DisplayName("Nested field sorting")
        void nestedFieldSorting() {
            assertNormalizedEquals(
                "{ user { profile { bio avatar } name id } }",
                "{ user { id name profile { avatar bio } } }"
            );
        }

        @Test
        @DisplayName("Arguments sorted")
        void argumentsSorted() {
            assertNormalizedEquals(
                "{ user(limit: 10, offset: 0, filter: \"active\") { id } }",
                "{ user(filter: \"active\", limit: 10, offset: 0) { id } }"
            );
        }

        @Test
        @DisplayName("Multiple fragments on same type merged")
        void multipleFragmentsSameTypeMerged() {
            assertNormalizedEquals(
                """
                { user { ...A ...B } }
                fragment A on User { id }
                fragment B on User { name }
                """,
                "{ user { id name } }"
            );
        }
    }

    // ==================== COMPLEX REAL-WORLD SCENARIOS ====================

    @Nested
    @DisplayName("Complex Real-World Scenarios")
    class ComplexRealWorldScenarios {

        @Test
        @DisplayName("GitHub-like issue query")
        void githubLikeIssueQuery() {
            String input = """
                query GetIssue($owner: String!, $repo: String!, $number: Int!) {
                    repository(owner: $owner, name: $repo) {
                        issue(number: $number) {
                            ...IssueFields
                            comments(first: 10) {
                                nodes {
                                    ...CommentFields
                                }
                                pageInfo {
                                    hasNextPage
                                    endCursor
                                }
                            }
                        }
                    }
                }
                fragment IssueFields on Issue {
                    id
                    title
                    body
                    state
                    author {
                        ...ActorFields
                    }
                    labels(first: 5) {
                        nodes {
                            name
                            color
                        }
                    }
                }
                fragment CommentFields on Comment {
                    id
                    body
                    author {
                        ...ActorFields
                    }
                    createdAt
                }
                fragment ActorFields on Actor {
                    login
                    avatarUrl
                }
                """;

            Document result = normalizer.normalize(parse(input));

            // All fragments should be inlined
            assertThat(result.getDefinitions()).hasSize(1);

            OperationDefinition op = getOperation(result);
            assertThat(op.getName()).isEqualTo("GetIssue");

            // Variables sorted
            assertThat(op.getVariableDefinitions().get(0).getName()).isEqualTo("number");
            assertThat(op.getVariableDefinitions().get(1).getName()).isEqualTo("owner");
            assertThat(op.getVariableDefinitions().get(2).getName()).isEqualTo("repo");
        }

        @Test
        @DisplayName("E-commerce cart query with conditional fields")
        void ecommerceCartQuery() {
            String input = """
                query GetCart($cartId: ID!, $includeRecommendations: Boolean!, $skipOutOfStock: Boolean!) {
                    cart(id: $cartId) {
                        id
                        items {
                            product {
                                id
                                name
                                price
                                inventory @skip(if: $skipOutOfStock) {
                                    quantity
                                    warehouse
                                }
                            }
                            quantity
                        }
                        subtotal
                        tax
                        total
                        recommendations @include(if: $includeRecommendations) {
                            ...ProductCard
                        }
                    }
                }
                fragment ProductCard on Product {
                    id
                    name
                    price
                    image {
                        url
                        alt
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));

            // Verify structure
            assertThat(result.getDefinitions()).hasSize(1);

            var cartSelections = getSelections(result, "cart");
            List<String> fieldNames = cartSelections.stream()
                .filter(s -> s instanceof Field)
                .map(s -> ((Field) s).getName())
                .toList();

            // Sorted and recommendations with directive preserved
            assertThat(fieldNames).contains("id", "items", "recommendations", "subtotal", "tax", "total");
        }

        @Test
        @DisplayName("Social media feed with polymorphic content")
        void socialMediaFeed() {
            String input = """
                query GetFeed($userId: ID!, $limit: Int = 20) {
                    feed(userId: $userId, limit: $limit) {
                        items {
                            __typename
                            ... on Post {
                                id
                                content
                                author { name avatar }
                                likes
                                comments { count }
                            }
                            ... on Photo {
                                id
                                url
                                caption
                                author { name avatar }
                                likes
                            }
                            ... on Video {
                                id
                                url
                                thumbnail
                                duration
                                author { name avatar }
                                views
                            }
                            ... on SharedPost {
                                id
                                originalPost {
                                    ... on Post { id content }
                                    ... on Photo { id url }
                                }
                                sharedBy { name }
                            }
                        }
                        pageInfo {
                            hasNextPage
                            cursor
                        }
                    }
                }
                """;

            Document result = normalizer.normalize(parse(input));

            var itemsSelections = getSelections(result, "feed", "items");

            // __typename first, then inline fragments sorted by type
            assertThat(((Field) itemsSelections.get(0)).getName()).isEqualTo("__typename");
            assertThat(((InlineFragment) itemsSelections.get(1)).getTypeCondition().getName()).isEqualTo("Photo");
            assertThat(((InlineFragment) itemsSelections.get(2)).getTypeCondition().getName()).isEqualTo("Post");
            assertThat(((InlineFragment) itemsSelections.get(3)).getTypeCondition().getName()).isEqualTo("SharedPost");
            assertThat(((InlineFragment) itemsSelections.get(4)).getTypeCondition().getName()).isEqualTo("Video");
        }
    }
}
