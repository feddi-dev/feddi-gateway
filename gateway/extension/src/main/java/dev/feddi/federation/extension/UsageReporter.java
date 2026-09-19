package dev.feddi.federation.extension;

/**
 * Reports usage data for executed GraphQL operations.
 *
 * <p>Implementations receive the {@link FeddiGatewayRequestContext} (request-side: document,
 * schema, headers, client info) and the {@link ExecutionOutcome} (response-side: duration,
 * errors). The implementation is responsible for extracting field coordinates, computing
 * operation hashes, batching, and delivering usage data.
 *
 * <p>Implementations must be non-blocking — the feddi Gateway calls {@link #report}
 * in the request path. Heavy processing (hashing, field extraction) should happen
 * on a background thread.
 */
public interface UsageReporter {

    /**
     * Report usage for a completed operation. Must be non-blocking.
     *
     * @param context the request context with document, schema, client info
     * @param outcome the execution result with duration and error info
     */
    void report(FeddiGatewayRequestContext context, ExecutionOutcome outcome);
}
