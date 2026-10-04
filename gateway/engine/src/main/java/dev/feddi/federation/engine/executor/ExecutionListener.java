package dev.feddi.federation.engine.executor;

/**
 * Listener for execution events. Implementations can record metrics,
 * log events, or perform other observability tasks.
 */
public interface ExecutionListener {

    ExecutionListener NOOP = new ExecutionListener() {};

    /**
     * Called when a subgraph fetch completes (successfully or with error).
     */
    default void onSubgraphFetchComplete(String subgraphName, long durationNanos, boolean success) {}

    /**
     * Called when a subgraph fetch times out.
     */
    default void onSubgraphTimeout(String subgraphName) {}

    /**
     * Called when one request to a subgraph carried several entity lookups (batching).
     *
     * @param subgraphName the subgraph
     * @param entityCount  the number of entities in the request
     */
    default void onSubgraphBatch(String subgraphName, int entityCount) {}
}
