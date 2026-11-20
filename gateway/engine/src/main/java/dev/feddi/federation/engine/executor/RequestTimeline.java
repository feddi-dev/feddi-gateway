package dev.feddi.federation.engine.executor;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.util.context.ContextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Experiment: the times of a sample of requests (every 8th) from the gateway's handler to the written response,
 * with each step's start, send and result, enabled with {@code -Dfeddi.experiment.timeline=true}. Every 20 s, the
 * median and p90 of each mark (ms after the handler started) are logged.
 */
public final class RequestTimeline {

    public static final boolean ENABLED = Boolean.getBoolean("feddi.experiment.timeline");
    /** The context key of a request's timeline. */
    public static final Object KEY = RequestTimeline.class;

    private static final Logger log = LoggerFactory.getLogger(RequestTimeline.class);
    private static final AtomicLong REQUESTS = new AtomicLong();
    private static final ConcurrentLinkedQueue<RequestTimeline> FINISHED = new ConcurrentLinkedQueue<>();

    static {
        if (ENABLED) {
            Thread reporter = new Thread(RequestTimeline::report, "feddi-timeline");
            reporter.setDaemon(true);
            reporter.start();
        }
    }

    private final long start = System.nanoTime();
    private final List<String> labels = new ArrayList<>(32);
    private final List<Long> times = new ArrayList<>(32);
    private final Map<String, Integer> occurrences = new HashMap<>();

    /** A timeline for a sampled request, or null. */
    public static @Nullable RequestTimeline begin() {
        return ENABLED && REQUESTS.getAndIncrement() % 8 == 0 ? new RequestTimeline() : null;
    }

    public static @Nullable RequestTimeline of(ContextView context) {
        return ENABLED ? context.getOrDefault(KEY, null) : null;
    }

    public synchronized void mark(String label) {
        labels.add(label);
        times.add(System.nanoTime());
    }

    /** Marks a label numbered by its occurrence in this request ({@code label#1}, {@code label#2}, …). */
    public synchronized void markNumbered(String label) {
        int n = occurrences.merge(label, 1, Integer::sum);
        mark(label + "#" + n);
    }

    public void finish() {
        mark("finished");
        FINISHED.add(this);
    }

    private static void report() {
        while (true) {
            try {
                Thread.sleep(Long.getLong("feddi.experiment.timelineMillis", 20_000L));
            } catch (InterruptedException e) {
                return;
            }
            Map<String, List<Double>> offsets = new HashMap<>();
            int requests = 0;
            RequestTimeline timeline;
            while ((timeline = FINISHED.poll()) != null) {
                requests++;
                synchronized (timeline) {
                    for (int i = 0; i < timeline.labels.size(); i++) {
                        offsets.computeIfAbsent(timeline.labels.get(i), l -> new ArrayList<>())
                            .add((timeline.times.get(i) - timeline.start) / 1e6);
                    }
                }
            }
            if (requests == 0) {
                continue;
            }
            StringBuilder out = new StringBuilder("timeline of ").append(requests).append(" requests (ms: n med p90)");
            int total = requests;
            offsets.entrySet().stream()
                .map(e -> {
                    List<Double> values = e.getValue();
                    values.sort(null);
                    return new Object[] {e.getKey(), values.size(), values.get(values.size() / 2),
                        values.get((int) (values.size() * 0.9))};
                })
                .sorted((a, b) -> Double.compare((Double) a[2], (Double) b[2]))
                .forEach(row -> out.append(String.format("%n  %-40s %6d %7.3f %7.3f", row[0], row[1], row[2], row[3])));
            log.warn("{} ({} sampled)", out, total);
        }
    }
}
