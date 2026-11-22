package dev.feddi.federation.app;

import io.netty.buffer.ByteBuf;
import io.netty.channel.AdaptiveRecvByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Experiment: per subgraph call, the socket-level times of the call on the gateway's side, enabled with
 * {@code -Dfeddi.experiment.subgraphWire=true}, logged every 20 s per subgraph port: from the request's write to its
 * flush, from the flush to the first read of the response, and from there to the last read, plus the reads and
 * read rounds (an event loop round ends with {@code channelReadComplete}) per response.
 * {@code -Dfeddi.experiment.recvBuf=N} sets the subgraph connections' initial receive buffer (Netty's adaptive
 * allocator, at most {@code max(N, 1 MiB)}).
 */
final class SubgraphWire {

    static final boolean ENABLED = Boolean.getBoolean("feddi.experiment.subgraphWire");
    static final int RECV_BUF = Integer.getInteger("feddi.experiment.recvBuf", 0);
    private static final Logger log = LoggerFactory.getLogger(SubgraphWire.class);
    private static final ConcurrentLinkedQueue<long[]> SAMPLES = new ConcurrentLinkedQueue<>();
    private static final String NAME = "feddi.subgraphWire";

    static {
        if (ENABLED) {
            Thread reporter = new Thread(SubgraphWire::report, "feddi-subgraph-wire");
            reporter.setDaemon(true);
            reporter.start();
        }
    }

    private SubgraphWire() {
    }

    /** The receive buffer allocator of the experiment, or null. */
    static @Nullable AdaptiveRecvByteBufAllocator recvAllocator() {
        return RECV_BUF <= 0 ? null : new AdaptiveRecvByteBufAllocator(64, RECV_BUF, Math.max(RECV_BUF, 1 << 20));
    }

    static void install(Channel channel, @Nullable SocketAddress remote) {
        if (ENABLED && channel.pipeline().get(NAME) == null) {
            int port = remote instanceof InetSocketAddress address ? address.getPort() : 0;
            channel.pipeline().addFirst(NAME, new Handler(port));
        }
    }

    private static final class Handler extends ChannelDuplexHandler {
        private final int port;
        private long writeCall;
        private long writeDone;
        private long firstRead;
        private long lastRead;
        private long reads;
        private long rounds;
        private long bytes;
        private boolean readInRound;

        Handler(int port) {
            this.port = port;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (writeCall == 0 || firstRead != 0) {
                finish();
                writeCall = System.nanoTime();
                ChannelPromise p = promise.unvoid();
                p.addListener(f -> writeDone = System.nanoTime());
                super.write(ctx, msg, p);
                return;
            }
            super.write(ctx, msg, promise);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (writeCall != 0 && msg instanceof ByteBuf buf) {
                long now = System.nanoTime();
                if (firstRead == 0) {
                    firstRead = now;
                }
                lastRead = now;
                reads++;
                bytes += buf.readableBytes();
                readInRound = true;
            }
            super.channelRead(ctx, msg);
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
            if (readInRound) {
                rounds++;
                readInRound = false;
            }
            super.channelReadComplete(ctx);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            finish();
            super.channelInactive(ctx);
        }

        private void finish() {
            if (writeCall != 0 && writeDone != 0 && firstRead != 0) {
                SAMPLES.add(new long[] {port, writeDone - writeCall, firstRead - writeDone, lastRead - firstRead,
                    reads, rounds, bytes});
            }
            writeCall = writeDone = firstRead = lastRead = reads = rounds = bytes = 0;
            readInRound = false;
        }
    }

    private static void report() {
        String[] names = {"write -> flushed (ms)", "flushed -> first read (ms)", "first -> last read (ms)", "reads",
            "read rounds", "bytes"};
        while (true) {
            try {
                Thread.sleep(Long.getLong("feddi.experiment.timelineMillis", 20_000L));
            } catch (InterruptedException e) {
                return;
            }
            Map<Long, List<long[]>> byPort = new TreeMap<>();
            long[] s;
            while ((s = SAMPLES.poll()) != null) {
                byPort.computeIfAbsent(s[0], k -> new ArrayList<>()).add(s);
            }
            if (byPort.isEmpty()) {
                continue;
            }
            StringBuilder out = new StringBuilder("subgraph wire times (med p90 p99)");
            byPort.forEach((port, samples) -> {
                out.append(String.format("%n port %d: %d calls", port, samples.size()));
                for (int i = 0; i < names.length; i++) {
                    int k = i + 1;
                    double scale = i < 3 ? 1e6 : 1;
                    double[] v = samples.stream().mapToDouble(x -> x[k] / scale).sorted().toArray();
                    out.append(String.format("%n   %-28s %9.3f %9.3f %9.3f", names[i], v[v.length / 2],
                        v[(int) (v.length * 0.9)], v[(int) (v.length * 0.99)]));
                }
            });
            log.warn("{}", out);
        }
    }
}
