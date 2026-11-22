package dev.feddi.federation.app;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.IoEventLoop;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SingleThreadIoEventLoop;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.internal.ThreadExecutorMap;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;
import reactor.netty.resources.LoopResources;
import reactor.util.context.ContextView;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Experiment: event loops that share work, enabled with {@code -Dfeddi.experiment.steal=defer|steal|wake}.
 *
 * <p>A subgraph response's work (parsing, merging, the next calls, the client's response) runs on the request's
 * {@link Strand}, which a loop queues in its own deque instead of running it inline: the loop returns to its I/O
 * first. With {@code defer} only the loop itself runs its deque (after its I/O). With {@code steal} a loop that has
 * nothing to do takes strands from the other loops' deques before it waits for I/O. With {@code wake} a loop that
 * queues a strand also wakes a loop waiting for I/O, if there is one, to take it.
 */
public final class StealingLoops implements LoopResources {

    enum Mode { DEFER, STEAL, WAKE }

    static final @Nullable Mode MODE = mode(System.getProperty("feddi.experiment.steal"));

    /** The context key of a request's strand. */
    public static final Object KEY = Strand.class;

    private final Group group;
    private final EventLoopGroup colocated;

    private StealingLoops(int loops) {
        IoHandlerFactory io = Epoll.isAvailable() ? EpollIoHandler.newFactory() : NioIoHandler.newFactory();
        this.group = new Group(loops, io);
        this.colocated = LoopResources.colocate(group);
    }

    /** The loops to install with {@code HttpResources.set}, or null if the experiment is off. */
    static @Nullable StealingLoops create(int loops) {
        return MODE == null ? null : new StealingLoops(loops);
    }

    private static @Nullable Mode mode(@Nullable String value) {
        return value == null || value.isEmpty() ? null : Mode.valueOf(value.toUpperCase(java.util.Locale.ROOT));
    }

    @Override
    public EventLoopGroup onServer(boolean useNative) {
        return group;
    }

    @Override
    public EventLoopGroup onServerSelect(boolean useNative) {
        return group;
    }

    @Override
    public EventLoopGroup onClient(boolean useNative) {
        // Connections on the calling loop, as with Reactor Netty's own loops
        return colocated;
    }

    @Override
    public boolean daemon() {
        return true;
    }

    @Override
    public Mono<Void> disposeLater(Duration quietPeriod, Duration timeout) {
        return Mono.fromRunnable(() -> group.shutdownGracefully(quietPeriod.toMillis(), timeout.toMillis(),
            java.util.concurrent.TimeUnit.MILLISECONDS));
    }

    /** The strand of the request whose context this is, or null. */
    static @Nullable Strand strand(ContextView context) {
        return MODE == null ? null : context.getOrDefault(KEY, null);
    }

    /** A new strand for a request, or null if the experiment is off. */
    static @Nullable Strand newStrand() {
        return MODE == null ? null : new Strand();
    }

    /**
     * The work of one request, run by one thread at a time in the order it was submitted. While it has work, it is
     * queued in the deque of the loop that submitted the first task, or held by the loop running it.
     */
    static final class Strand implements Runnable {
        private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
        private final AtomicInteger pending = new AtomicInteger();

        void submit(Runnable task) {
            tasks.add(task);
            if (pending.getAndIncrement() == 0) {
                EventExecutor executor = ThreadExecutorMap.currentExecutor();
                if (executor instanceof Loop loop) {
                    loop.queue(this);
                } else {
                    run();
                }
            }
        }

        @Override
        public void run() {
            do {
                Runnable task = tasks.poll();
                if (task != null) {
                    task.run();
                }
            } while (pending.decrementAndGet() != 0);
        }
    }

    private static final class Group extends MultiThreadIoEventLoopGroup {
        /** The group's loops, filled while the group is constructed. */
        private final List<Loop> loops;

        Group(int threads, IoHandlerFactory io) {
            super(threads, new DefaultThreadFactory("feddi-loop", true), io);
            List<Loop> all = new ArrayList<>();
            for (EventExecutor executor : this) {
                all.add((Loop) executor);
            }
            this.loops = List.copyOf(all);
            for (Loop loop : loops) {
                loop.group = this;
            }
        }

        @Override
        protected IoEventLoop newChild(Executor executor, IoHandlerFactory io, Object... args) {
            return new Loop(this, executor, io);
        }

        /** A strand from another loop than {@code thief}, or null. */
        @Nullable Strand steal(Loop thief) {
            List<Loop> all = loops;
            int n = all.size();
            int start = thief.index;
            for (int i = 1; i < n; i++) {
                Strand strand = all.get((start + i) % n).deque.pollLast();
                if (strand != null) {
                    return strand;
                }
            }
            return null;
        }

        boolean othersHaveWork(Loop thief) {
            for (Loop loop : loops) {
                if (loop != thief && !loop.deque.isEmpty()) {
                    return true;
                }
            }
            return false;
        }

        /** Wakes one loop waiting for I/O, if there is one, to take a strand. */
        void wakeIdle(Loop from) {
            for (Loop loop : loops) {
                if (loop != from && loop.idle) {
                    loop.idle = false;
                    loop.execute(loop.stealOne);
                    return;
                }
            }
        }
    }

    private static final class Loop extends SingleThreadIoEventLoop {
        private static final AtomicInteger INDEX = new AtomicInteger();

        final int index = INDEX.getAndIncrement();
        final ConcurrentLinkedDeque<Strand> deque = new ConcurrentLinkedDeque<>();
        volatile boolean idle;
        @Nullable Group group;
        /** Whether a task that runs this loop's deque is queued (only touched by this loop's thread). */
        private boolean draining;

        private final Runnable drain = () -> {
            draining = false;
            Strand strand;
            while ((strand = deque.pollFirst()) != null) {
                strand.run();
            }
        };

        final Runnable stealOne = () -> {
            Group g = group;
            Strand strand = g == null ? null : g.steal(this);
            if (strand != null) {
                strand.run();
            }
        };

        Loop(Group group, Executor executor, IoHandlerFactory io) {
            super(group, executor, io);
        }

        /** Queues a strand on this loop (called on this loop's thread). */
        void queue(Strand strand) {
            deque.addLast(strand);
            if (!draining) {
                draining = true;
                execute(drain);
            }
            Group g = group;
            if (MODE == Mode.WAKE && g != null) {
                g.wakeIdle(this);
            }
        }

        @Override
        protected int runIo() {
            Group g = group;
            if (MODE != Mode.DEFER && g != null && !hasTasks()) {
                if (g.othersHaveWork(this)) {
                    // A queued task keeps the I/O poll from blocking; the loop takes the strand after it
                    execute(stealOne);
                } else if (MODE == Mode.WAKE) {
                    idle = true;
                    if (g.othersHaveWork(this)) {
                        idle = false;
                        execute(stealOne);
                    }
                }
            }
            try {
                return super.runIo();
            } finally {
                idle = false;
            }
        }
    }
}
