package dev.feddi.federation.engine.testutil;

import graphql.language.Document;
import graphql.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the IntrospectionQuery utility.
 */
class IntrospectionQueryTest {

    @Test
    void defaultIntrospectionQueryIsValidGraphQL() {
        String query = IntrospectionQuery.getIntrospectionQuery();

        // Should parse without errors
        Document doc = Parser.parse(query);
        assertThat(doc.getDefinitions()).isNotEmpty();

        // Should contain key elements
        assertThat(query).contains("__schema");
        assertThat(query).contains("queryType");
        assertThat(query).contains("mutationType");
        assertThat(query).contains("types");
        assertThat(query).contains("directives");
        assertThat(query).contains("fragment FullType");
        assertThat(query).contains("fragment InputValue");
        assertThat(query).contains("fragment TypeRef");

        // Default should include descriptions
        assertThat(query).contains("description");
    }

    @Test
    void introspectionQueryWithoutDescriptions() {
        IntrospectionQuery.Options options = IntrospectionQuery.Options.builder()
            .descriptions(false)
            .build();

        String query = IntrospectionQuery.getIntrospectionQuery(options);

        // Should parse without errors
        Document doc = Parser.parse(query);
        assertThat(doc.getDefinitions()).isNotEmpty();

        // Should NOT contain description field (except in fragment names)
        String withoutFragments = query
            .replace("fragment FullType", "")
            .replace("fragment InputValue", "");
        // The query should have minimal description mentions
        assertThat(withoutFragments.split("description").length).isLessThan(5);
    }

    @Test
    void introspectionQueryWithAllOptions() {
        IntrospectionQuery.Options options = IntrospectionQuery.Options.builder()
            .descriptions(true)
            .specifiedByUrl(true)
            .directiveIsRepeatable(true)
            .schemaDescription(true)
            .inputValueDeprecation(true)
            .oneOf(true)
            .build();

        String query = IntrospectionQuery.getIntrospectionQuery(options);

        // Should parse without errors
        Document doc = Parser.parse(query);
        assertThat(doc.getDefinitions()).isNotEmpty();

        // Should contain all optional fields
        assertThat(query).contains("specifiedByURL");
        assertThat(query).contains("isRepeatable");
        assertThat(query).contains("isOneOf");
        assertThat(query).contains("includeDeprecated: true");
        assertThat(query).contains("isDeprecated");
        assertThat(query).contains("deprecationReason");
    }

    @Test
    void typeNamesQueryIsValidGraphQL() {
        String query = IntrospectionQuery.getTypeNamesQuery();

        Document doc = Parser.parse(query);
        assertThat(doc.getDefinitions()).isNotEmpty();

        assertThat(query).contains("__schema");
        assertThat(query).contains("types");
        assertThat(query).contains("name");
        assertThat(query).contains("kind");
    }

    @Test
    void typeQueryIsValidGraphQL() {
        String query = IntrospectionQuery.getTypeQuery("Product");

        Document doc = Parser.parse(query);
        assertThat(doc.getDefinitions()).isNotEmpty();

        assertThat(query).contains("__type(name: \"Product\")");
        assertThat(query).contains("fields");
        assertThat(query).contains("inputFields");
        assertThat(query).contains("enumValues");
        assertThat(query).contains("possibleTypes");
    }

    @Test
    void typeRefFragmentHasSufficientDepth() {
        String query = IntrospectionQuery.getIntrospectionQuery();

        // Count nesting levels in TypeRef - should handle deeply nested types
        // like [[[[String]]]] (NonNull of List of NonNull of List of String)
        int ofTypeCount = query.split("ofType").length - 1;

        // Should have at least 7 levels of nesting (standard is ~9)
        assertThat(ofTypeCount).isGreaterThanOrEqualTo(7);
    }
}
