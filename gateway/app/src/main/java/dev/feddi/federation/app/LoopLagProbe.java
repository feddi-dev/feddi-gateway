package dev.feddi.federation.app;

import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.netty.http.HttpResources;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

/**
 * Experiment: how long a task waits for its event loop, enabled with {@code -Dfeddi.experiment.loopLag=true}.
 * A probe thread submits a task to every loop of the shared HTTP resources each millisecond; the task records the
 * time from its submission to its start. Every {@code feddi.experiment.timelineMillis} (20 s), the lag's
 * percentiles are logged per loop and overall.
 */
final class LoopLagProbe {

    static final boolean ENABLED = Boolean.getBoolean("feddi.experiment.loopLag");

    private static final Logger log = LoggerFactory.getLogger(LoopLagProbe.class);
    private static final long INTERVAL_NANOS = 1_000_000L;

    private static volatile boolean started;

    private LoopLagProbe() {
    }

    /** Starts the probe once, if enabled. */
    static synchronized void start() {
        if (!ENABLED || started) {
            return;
        }
        started = true;
        EventLoopGroup group = HttpResources.get().onServer(true);
        List<Recorder> recorders = new ArrayList<>();
        for (EventExecutor executor : group) {
            recorders.add(new Recorder(executor));
        }
        Thread probe = new Thread(() -> probe(recorders), "feddi-loop-lag");
        probe.setDaemon(true);
        probe.start();
        log.warn("loop lag probe started on {} loops", recorders.size());
    }

    private static void probe(List<Recorder> recorders) {
        long interval = Long.getLong("feddi.experiment.timelineMillis", 20_000L) * 1_000_000L;
        long nextReport = System.nanoTime() + interval;
        long next = System.nanoTime();
        while (true) {
            next += INTERVAL_NANOS;
            long wait = next - System.nanoTime();
            if (wait > 0) {
                LockSupport.parkNanos(wait);
            }
            for (Recorder recorder : recorders) {
                recorder.submit();
            }
            if (System.nanoTime() >= nextReport) {
                nextReport += interval;
                report(recorders);
            }
        }
    }

    private static void report(List<Recorder> recorders) {
        StringBuilder out = new StringBuilder("loop lag (us: n p50 p90 p99 p99.9 max; pending tasks p50 p99)");
        List<long[]> all = new ArrayList<>();
        int total = 0;
        for (int i = 0; i < recorders.size(); i++) {
            long[][] taken = recorders.get(i).take();
            all.add(taken[0]);
            total += taken[0].length;
            out.append(String.format("%n  loop %-3d %s %s", i, percentiles(taken[0]), pending(taken[1])));
        }
        long[] merged = new long[total];
        int offset = 0;
        for (long[] lags : all) {
            System.arraycopy(lags, 0, merged, offset, lags.length);
            offset += lags.length;
        }
        out.append(String.format("%n  all      %s", percentiles(merged)));
        log.warn("{}", out);
    }

    private static String percentiles(long[] lags) {
        if (lags.length == 0) {
            return "n=0";
        }
        Arrays.sort(lags);
        return String.format("%7d %7.0f %7.0f %7.0f %7.0f %7.0f", lags.length, at(lags, 0.5), at(lags, 0.9),
            at(lags, 0.99), at(lags, 0.999), lags[lags.length - 1] / 1e3);
    }

    private static String pending(long[] pending) {
        if (pending.length == 0) {
            return "";
        }
        Arrays.sort(pending);
        return String.format("%4d %4d", pending[pending.length / 2], pending[(int) (pending.length * 0.99)]);
    }

    private static double at(long[] sorted, double quantile) {
        return sorted[Math.min(sorted.length - 1, (int) (sorted.length * quantile))] / 1e3;
    }

    /** The lags of one loop, written by its loop thread, taken by the probe thread. */
    private static final class Recorder {

        private final EventExecutor executor;
        private long[] lags = new long[32_768];
        private long[] pending = new long[32_768];
        private int count;
        private boolean inFlight;

        Recorder(EventExecutor executor) {
            this.executor = executor;
        }

        /** Submits a probe task, unless the previous one has not run yet (its lag keeps growing). */
        void submit() {
            synchronized (this) {
                if (inFlight) {
                    return;
                }
                inFlight = true;
            }
            long submitted = System.nanoTime();
            int queued = executor instanceof SingleThreadEventExecutor single
                ? single.pendingTasks() : -1;
            executor.execute(() -> record(System.nanoTime() - submitted, queued));
        }

        private synchronized void record(long lag, int queued) {
            inFlight = false;
            if (count == lags.length) {
                lags = Arrays.copyOf(lags, count * 2);
                pending = Arrays.copyOf(pending, count * 2);
            }
            lags[count] = lag;
            pending[count] = queued;
            count++;
        }

        synchronized long[][] take() {
            long[][] taken = {Arrays.copyOf(lags, count), Arrays.copyOf(pending, count)};
            count = 0;
            return taken;
        }
    }
}
