package dev.feddi.federation.engine.executor;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Controls the order in which subgraph calls complete during testing.
 *
 * This allows testing parallel execution behavior by specifying that certain
 * calls should complete before others, regardless of when they actually start.
 *
 * Format: "1,2[1],2[0],3" means:
 * - Call with id "1" completes first
 * - Call with id "2", second execution (index 1), completes second
 * - Call with id "2", first execution (index 0), completes third
 * - Call with id "3" completes fourth
 */
public final class FinishOrderController {

    private static final Pattern INDEXED_ID_PATTERN = Pattern.compile("^(.+)\\[(\\d+)]$");
    private static final long STEP_DELAY_MS = 10;

    private final List<OrderEntry> finishOrder;
    private final Map<String, Sinks.One<Void>> completionSignals = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> executionCounters = new ConcurrentHashMap<>();
    private final AtomicInteger completedCount = new AtomicInteger(0);

    /**
     * Creates a controller with no ordering constraints.
     * All calls will complete immediately with a small random delay.
     */
    public FinishOrderController() {
        this.finishOrder = null;
    }

    /**
     * Creates a controller with the specified finish order.
     *
     * @param finishOrderSpec comma-separated list of call IDs in completion order
     *                        (e.g., "1,3,2" or "1,2[1],2[0],3")
     */
    public FinishOrderController(String finishOrderSpec) {
        if (finishOrderSpec == null || finishOrderSpec.isBlank()) {
            this.finishOrder = null;
        } else {
            this.finishOrder = parseFinishOrder(finishOrderSpec);
            initializeSignals();
        }
    }

    /**
     * Parses the finish order specification into a list of OrderEntry objects.
     */
    private List<OrderEntry> parseFinishOrder(String spec) {
        List<OrderEntry> order = new ArrayList<>();
        String[] parts = spec.split(",");

        for (String part : parts) {
            part = part.trim();
            Matcher matcher = INDEXED_ID_PATTERN.matcher(part);

            if (matcher.matches()) {
                // Indexed format: "2[1]"
                String id = matcher.group(1);
                int index = Integer.parseInt(matcher.group(2));
                order.add(new OrderEntry(id, index));
            } else {
                // Simple format: "1" (defaults to index -1 meaning "any/first")
                order.add(new OrderEntry(part, -1));
            }
        }

        return order;
    }

    /**
     * Initializes completion signals for all entries in the finish order.
     */
    private void initializeSignals() {
        for (OrderEntry entry : finishOrder) {
            String key = entry.toKey();
            completionSignals.put(key, Sinks.one());
        }
        // Emit the first signal immediately
        if (!finishOrder.isEmpty()) {
            String firstKey = finishOrder.get(0).toKey();
            completionSignals.get(firstKey).tryEmitEmpty();
        }
    }

    /**
     * Called when a subgraph call starts. Returns a Mono that completes
     * when this call should finish (based on the finish order).
     *
     * @param callId the ID of the call
     * @return a Mono that completes when this call should finish
     */
    public Mono<Void> waitForTurn(String callId) {
        if (finishOrder == null || callId == null) {
            // No ordering - complete with small delay
            return Mono.delay(Duration.ofMillis(STEP_DELAY_MS)).then();
        }

        // Get the execution index for this call ID (for handling repeated calls)
        int executionIndex = executionCounters
            .computeIfAbsent(callId, k -> new AtomicInteger(0))
            .getAndIncrement();

        // Find this call in the finish order
        int orderIndex = findOrderIndex(callId, executionIndex);

        if (orderIndex == -1) {
            // Not in finish order - complete with small delay
            return Mono.delay(Duration.ofMillis(STEP_DELAY_MS)).then();
        }

        String myKey = finishOrder.get(orderIndex).toKey();
        Sinks.One<Void> mySignal = completionSignals.get(myKey);

        if (mySignal == null) {
            // Shouldn't happen, but fall back to immediate completion
            return Mono.delay(Duration.ofMillis(STEP_DELAY_MS)).then();
        }

        // Wait for our signal, then signal the next call in the order
        return mySignal.asMono()
            .then(Mono.delay(Duration.ofMillis(STEP_DELAY_MS)))
            .then()
            .doOnSuccess(v -> signalNext(orderIndex));
    }

    /**
     * Signals the next call in the finish order to complete.
     */
    private void signalNext(int currentIndex) {
        int nextIndex = currentIndex + 1;
        if (nextIndex < finishOrder.size()) {
            String nextKey = finishOrder.get(nextIndex).toKey();
            Sinks.One<Void> nextSignal = completionSignals.get(nextKey);
            if (nextSignal != null) {
                nextSignal.tryEmitEmpty();
            }
        }
    }

    /**
     * Finds the index in the finish order for a call with the given ID and execution index.
     */
    private int findOrderIndex(String callId, int executionIndex) {
        // First, try to find an exact match with the execution index
        for (int i = 0; i < finishOrder.size(); i++) {
            OrderEntry entry = finishOrder.get(i);
            if (entry.id.equals(callId) && entry.index == executionIndex) {
                return i;
            }
        }

        // Then, try to find a non-indexed entry (for calls that aren't repeated)
        for (int i = 0; i < finishOrder.size(); i++) {
            OrderEntry entry = finishOrder.get(i);
            if (entry.id.equals(callId) && entry.index == -1) {
                return i;
            }
        }

        return -1;
    }

    /**
     * Represents an entry in the finish order.
     */
    private record OrderEntry(String id, int index) {
        String toKey() {
            if (index == -1) {
                return id;
            }
            return id + "[" + index + "]";
        }
    }
}
