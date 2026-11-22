package dev.feddi.federation.app;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.reactor.netty.NettyServerCustomizer;
import org.springframework.stereotype.Component;
import reactor.netty.NettyPipeline;
import reactor.netty.http.server.HttpServer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Experiment: per request on the wire, the times from the request's head to its last content, from there to the
 * response's head being written, and from there to the response's last content being flushed; enabled with
 * {@code -Dfeddi.experiment.wireTimes=true}, logged every 20 s.
 */
@Component
public class WireTimes implements NettyServerCustomizer {

    static final boolean ENABLED = Boolean.getBoolean("feddi.experiment.wireTimes");
    private static final Logger log = LoggerFactory.getLogger(WireTimes.class);
    private static final ConcurrentLinkedQueue<long[]> SAMPLES = new ConcurrentLinkedQueue<>();
    private static final String NAME = "feddi.wireTimes";

    static {
        if (ENABLED) {
            Thread reporter = new Thread(WireTimes::report, "feddi-wire-times");
            reporter.setDaemon(true);
            reporter.start();
        }
    }

    @Override
    public HttpServer apply(HttpServer server) {
        if (!ENABLED) {
            return server;
        }
        return server.doOnConnection(connection -> {
            if (connection.channel().pipeline().get(NAME) == null
                && connection.channel().pipeline().get(NettyPipeline.HttpCodec) != null) {
                connection.channel().pipeline().addAfter(NettyPipeline.HttpCodec, NAME, new Handler());
            }
        });
    }

    private static final class Handler extends ChannelDuplexHandler {
        private long head;
        private long last;
        private long responseHead;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            long now = System.nanoTime();
            if (msg instanceof HttpRequest) {
                head = now;
            }
            if (msg instanceof LastHttpContent) {
                last = now;
            }
            super.channelRead(ctx, msg);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg instanceof HttpResponse) {
                responseHead = System.nanoTime();
            }
            if (msg instanceof LastHttpContent && head != 0) {
                long h = head;
                long l = last;
                long r = responseHead;
                ChannelPromise p = promise.unvoid();
                p.addListener(f -> SAMPLES.add(new long[] {l - h, r - l, System.nanoTime() - r}));
                super.write(ctx, msg, p);
                return;
            }
            super.write(ctx, msg, promise);
        }
    }

    private static void report() {
        String[] names = {"request head -> last content", "last content -> response head", "response head -> flushed"};
        while (true) {
            try {
                Thread.sleep(Long.getLong("feddi.experiment.timelineMillis", 20_000L));
            } catch (InterruptedException e) {
                return;
            }
            List<long[]> samples = new ArrayList<>();
            long[] s;
            while ((s = SAMPLES.poll()) != null) {
                samples.add(s);
            }
            if (samples.isEmpty()) {
                continue;
            }
            StringBuilder out = new StringBuilder("wire times of ").append(samples.size()).append(" requests (ms: med p90 p99)");
            for (int i = 0; i < names.length; i++) {
                int k = i;
                double[] v = samples.stream().mapToDouble(x -> x[k] / 1e6).sorted().toArray();
                out.append(String.format("%n  %-32s %7.3f %7.3f %7.3f", names[i], v[v.length / 2],
                    v[(int) (v.length * 0.9)], v[(int) (v.length * 0.99)]));
            }
            log.warn("{}", out);
        }
    }
}
