package dev.feddi.federation.app;

import org.jspecify.annotations.Nullable;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.ForkJoinPool;

/**
 * Experiment: a work-stealing pool for the gateway's CPU work, so that it is not tied to the event loop of a
 * connection. {@code -Dfeddi.experiment.pool=responses} moves the work after a subgraph response has arrived (parsing,
 * merging, the next round's requests and building the client's response) to the pool; {@code =all} also moves the
 * work after the client's request body has arrived (parsing and planning). {@code -Dfeddi.experiment.poolThreads}
 * sets its size (default: the available processors).
 */
final class SharedPool {

    private static final String MODE = System.getProperty("feddi.experiment.pool", "none");

    /** The pool for the work after a subgraph response, or null. */
    static final @Nullable Scheduler RESPONSES;
    /** The pool for the work after the client's request, or null. */
    static final @Nullable Scheduler REQUESTS;

    static {
        Scheduler pool = null;
        if (!MODE.equals("none")) {
            int threads = Integer.getInteger("feddi.experiment.poolThreads", Runtime.getRuntime().availableProcessors());
            ForkJoinPool forkJoin = new ForkJoinPool(threads, p -> {
                var thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(p);
                thread.setName("feddi-pool-" + thread.getPoolIndex());
                thread.setDaemon(true);
                return thread;
            }, null, true);
            pool = Schedulers.fromExecutorService(forkJoin, "feddi-pool");
        }
        RESPONSES = pool;
        REQUESTS = MODE.equals("all") ? pool : null;
    }

    private SharedPool() {
    }
}
