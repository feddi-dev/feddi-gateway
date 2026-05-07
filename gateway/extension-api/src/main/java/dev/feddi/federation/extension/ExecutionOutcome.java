package dev.feddi.federation.extension;

import graphql.language.Document;

/**
 * Result data from a completed GraphQL operation execution.
 * Passed to {@link UsageReporter#report} alongside the request context.
 *
 * @param durationNanos execution duration in nanoseconds
 * @param httpError true if a subgraph returned a non-2xx HTTP status
 * @param graphqlError true if the response contains at least one GraphQL error
 * @param document the parsed and validated GraphQL document (for usage reporting)
 */
public record ExecutionOutcome(
    long durationNanos,
    boolean httpError,
    boolean graphqlError,
    Document document
) {}
