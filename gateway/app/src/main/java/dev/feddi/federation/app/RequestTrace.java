package dev.feddi.federation.app;

import dev.feddi.federation.extension.FeddiGatewayRequestContext;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.server.ServerWebExchange;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Experiment: the subgraph calls of every 50th request with their times, logged as one line when the response is
 * written ({@code -Dfeddi.trace=true}). Times are microseconds since the request reached the first web filter
 * ({@link TraceWebFilter}).
 */
final class RequestTrace {

    private static final Logger log = LoggerFactory.getLogger(RequestTrace.class);
    static final boolean ENABLED = Boolean.getBoolean("feddi.trace");
    private static final int EVERY = 50;
    private static final AtomicLong REQUESTS = new AtomicLong();
    private static final Map<FeddiGatewayRequestContext, RequestTrace> ACTIVE =
        Collections.synchronizedMap(new IdentityHashMap<>());

    /** The exchange attribute holding the time the request reached the first filter. */
    static final String ARRIVED = "feddi.trace.arrived";
    /** The exchange attribute holding the request's trace. */
    static final String TRACE = "feddi.trace";

    private final long start;
    private final long handled = System.nanoTime();
    private long executed;
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    private RequestTrace(long start) {
        this.start = start;
    }

    /** Starts tracing the request when it is sampled. */
    static void start(FeddiGatewayRequestContext context, ServerWebExchange exchange) {
        if (ENABLED && REQUESTS.incrementAndGet() % EVERY == 0) {
            Long arrived = exchange.getAttribute(ARRIVED);
            RequestTrace trace = new RequestTrace(arrived != null ? arrived : System.nanoTime());
            ACTIVE.put(context, trace);
            exchange.getAttributes().put(TRACE, trace);
        }
    }

    /** The trace of the request, if it is traced. */
    static @Nullable RequestTrace of(FeddiGatewayRequestContext context) {
        return ENABLED && !ACTIVE.isEmpty() ? ACTIVE.get(context) : null;
    }

    /** Records that the response is serialized, if the request is traced. */
    static void finish(FeddiGatewayRequestContext context) {
        if (!ENABLED) {
            return;
        }
        RequestTrace trace = ACTIVE.remove(context);
        if (trace != null) {
            trace.executed = System.nanoTime();
        }
    }

    /** Logs the trace once the response is written. */
    void written(long nanos) {
        log.info("TRACE total={} handled={} executed={} thread={} calls={}", micros(nanos), micros(handled),
            micros(executed), Thread.currentThread().getName(), calls);
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
