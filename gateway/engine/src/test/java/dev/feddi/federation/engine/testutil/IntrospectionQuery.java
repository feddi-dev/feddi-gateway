package dev.feddi.federation.engine.testutil;

/**
 * Utility for generating GraphQL introspection queries with configurable options.
 * Equivalent to graphql-js's getIntrospectionQuery function.
 *
 * <p>The generated query fetches complete schema metadata including types,
 * fields, directives, and their relationships.
 */
public final class IntrospectionQuery {

    private IntrospectionQuery() {
        // Utility class
    }

    /**
     * Options for configuring the introspection query.
     */
    public record Options(
        /** Whether to include description fields. Default: true */
        boolean descriptions,
        /** Whether to include specifiedByURL for custom scalars. Default: false */
        boolean specifiedByUrl,
        /** Whether to include isRepeatable flag on directives. Default: false */
        boolean directiveIsRepeatable,
        /** Whether to include schema description. Default: false */
        boolean schemaDescription,
        /** Whether to include deprecation on input values. Default: false */
        boolean inputValueDeprecation,
        /** Whether to include isOneOf on input objects. Default: false */
        boolean oneOf
    ) {
        /**
         * Creates options with default values.
         */
        public static Options defaults() {
            return new Options(true, false, false, false, false, false);
        }

        /**
         * Builder for creating custom options.
         */
        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private boolean descriptions = true;
            private boolean specifiedByUrl = false;
            private boolean directiveIsRepeatable = false;
            private boolean schemaDescription = false;
            private boolean inputValueDeprecation = false;
            private boolean oneOf = false;

            public Builder descriptions(boolean value) {
                this.descriptions = value;
                return this;
            }

            public Builder specifiedByUrl(boolean value) {
                this.specifiedByUrl = value;
                return this;
            }

            public Builder directiveIsRepeatable(boolean value) {
                this.directiveIsRepeatable = value;
                return this;
            }

            public Builder schemaDescription(boolean value) {
                this.schemaDescription = value;
                return this;
            }

            public Builder inputValueDeprecation(boolean value) {
                this.inputValueDeprecation = value;
                return this;
            }

            public Builder oneOf(boolean value) {
                this.oneOf = value;
                return this;
            }

            public Options build() {
                return new Options(descriptions, specifiedByUrl, directiveIsRepeatable,
                    schemaDescription, inputValueDeprecation, oneOf);
            }
        }
    }

    /**
     * Generates an introspection query with default options.
     */
    public static String getIntrospectionQuery() {
        return getIntrospectionQuery(Options.defaults());
    }

    /**
     * Generates an introspection query with the specified options.
     *
     * @param options the options controlling which fields to include
     * @return the introspection query string
     */
    public static String getIntrospectionQuery(Options options) {
        String descriptions = options.descriptions() ? "description" : "";
        String specifiedByUrl = options.specifiedByUrl() ? "specifiedByURL" : "";
        String directiveIsRepeatable = options.directiveIsRepeatable() ? "isRepeatable" : "";
        String schemaDescription = options.schemaDescription() ? "description" : "";
        String inputValueDeprecation = options.inputValueDeprecation()
            ? "isDeprecated deprecationReason"
            : "";
        String oneOf = options.oneOf() ? "isOneOf" : "";

        return """
            query IntrospectionQuery {
              __schema {
                %s
                queryType { name }
                mutationType { name }
                subscriptionType { name }
                types {
                  ...FullType
                }
                directives {
                  name
                  %s
                  %s
                  locations
                  args%s {
                    ...InputValue
                  }
                }
              }
            }

            fragment FullType on __Type {
              kind
              name
              %s
              %s
              %s
              fields(includeDeprecated: true) {
                name
                %s
                args%s {
                  ...InputValue
                }
                type {
                  ...TypeRef
                }
                isDeprecated
                deprecationReason
              }
              inputFields%s {
                ...InputValue
              }
              interfaces {
                ...TypeRef
              }
              enumValues(includeDeprecated: true) {
                name
                %s
                isDeprecated
                deprecationReason
              }
              possibleTypes {
                ...TypeRef
              }
            }

            fragment InputValue on __InputValue {
              name
              %s
              type {
                ...TypeRef
              }
              defaultValue
              %s
            }

            fragment TypeRef on __Type {
              kind
              name
              ofType {
                kind
                name
                ofType {
                  kind
                  name
                  ofType {
                    kind
                    name
                    ofType {
                      kind
                      name
                      ofType {
                        kind
                        name
                        ofType {
                          kind
                          name
                          ofType {
                            kind
                            name
                            ofType {
                              kind
                              name
                              ofType {
                                kind
                                name
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
            """.formatted(
                schemaDescription,                                    // __schema description
                descriptions,                                         // directive description
                directiveIsRepeatable,                                // directive isRepeatable
                inputValueDeprecation.isEmpty() ? "" : "(includeDeprecated: true)", // directive args
                descriptions,                                         // FullType description
                specifiedByUrl,                                       // FullType specifiedByURL
                oneOf,                                                // FullType isOneOf
                descriptions,                                         // field description
                inputValueDeprecation.isEmpty() ? "" : "(includeDeprecated: true)", // field args
                inputValueDeprecation.isEmpty() ? "" : "(includeDeprecated: true)", // inputFields
                descriptions,                                         // enumValue description
                descriptions,                                         // InputValue description
                inputValueDeprecation                                 // InputValue deprecation
            );
    }

    /**
     * Generates a minimal introspection query that only fetches type names.
     * Useful for quick schema validation.
     */
    public static String getTypeNamesQuery() {
        return """
            {
              __schema {
                types {
                  name
                  kind
                }
              }
            }
            """;
    }

    /**
     * Generates an introspection query for a specific type.
     *
     * @param typeName the name of the type to introspect
     * @return the introspection query string
     */
    public static String getTypeQuery(String typeName) {
        return """
            {
              __type(name: "%s") {
                kind
                name
                description
                fields(includeDeprecated: true) {
                  name
                  description
                  args {
                    name
                    description
                    type {
                      ...TypeRef
                    }
                    defaultValue
                  }
                  type {
                    ...TypeRef
                  }
                  isDeprecated
                  deprecationReason
                }
                inputFields {
                  name
                  description
                  type {
                    ...TypeRef
                  }
                  defaultValue
                }
                interfaces {
                  ...TypeRef
                }
                enumValues(includeDeprecated: true) {
                  name
                  description
                  isDeprecated
                  deprecationReason
                }
                possibleTypes {
                  ...TypeRef
                }
              }
            }

            fragment TypeRef on __Type {
              kind
              name
              ofType {
                kind
                name
                ofType {
                  kind
                  name
                  ofType {
                    kind
                    name
                    ofType {
                      kind
                      name
                      ofType {
                        kind
                        name
                        ofType {
                          kind
                          name
                        }
                      }
                    }
                  }
                }
              }
            }
            """.formatted(typeName);
    }
}
