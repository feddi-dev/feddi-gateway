package dev.feddi.federation.app;

import dev.feddi.federation.extension.FeddiGatewayRequestContext;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Experiment: the subgraph calls of every 50th request with their times, logged as one line when the request is
 * done ({@code -Dfeddi.trace=true}). Times are microseconds since the request started.
 */
final class RequestTrace {

    private static final Logger log = LoggerFactory.getLogger(RequestTrace.class);
    static final boolean ENABLED = Boolean.getBoolean("feddi.trace");
    private static final int EVERY = 50;
    private static final AtomicLong REQUESTS = new AtomicLong();
    private static final Map<FeddiGatewayRequestContext, RequestTrace> ACTIVE =
        Collections.synchronizedMap(new IdentityHashMap<>());

    private final long start = System.nanoTime();
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    private RequestTrace() {
    }

    /** Starts tracing the request when it is sampled. */
    static void start(FeddiGatewayRequestContext context) {
        if (ENABLED && REQUESTS.incrementAndGet() % EVERY == 0) {
            ACTIVE.put(context, new RequestTrace());
        }
    }

    /** The trace of the request, if it is traced. */
    static @Nullable RequestTrace of(FeddiGatewayRequestContext context) {
        return ENABLED && !ACTIVE.isEmpty() ? ACTIVE.get(context) : null;
    }

    /** Logs the request's trace, if it is traced. */
    static void finish(FeddiGatewayRequestContext context) {
        if (!ENABLED) {
            return;
        }
        RequestTrace trace = ACTIVE.remove(context);
        if (trace != null) {
            log.info("TRACE total={} thread={} calls={}", trace.micros(System.nanoTime()),
                Thread.currentThread().getName(), trace.calls);
        }
    }

    long micros(long nanos) {
        return (nanos - start) / 1000;
    }

    /**
     * Records a call: subscribed, request written (connection acquired), response headers, response read.
     */
    void call(String subgraph, String label, long subscribed, long written, long headers, long read,
              String thread) {
        calls.add(subgraph + "/" + label + " " + micros(subscribed) + " " + micros(written) + " "
            + micros(headers) + " " + micros(read) + " " + thread);
    }
}
