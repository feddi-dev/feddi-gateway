package dev.feddi.federation.app;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.ThreadExecutorMap;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.netty.resources.LoopResources;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * HTTP/1.1 connections to one subgraph address on plain Netty, kept per event loop: a call made on an event loop
 * uses a connection of that loop, with no locks, no pool borrow through Reactor and no per-call channel setup. Each
 * loop opens at most {@code maxConnections}; further calls wait for a connection of that loop.
 */
final class LoopConnections {

    /** A subgraph's HTTP response: its status and body (empty if it had none). */
    record Response(int status, byte[] body) {
    }

    private static final byte[] EMPTY = new byte[0];

    private final LoopResources loopResources;
    private final SocketAddress address;
    private final String host;
    private final int maxConnections;
    private final long maxIdleNanos;
    private final int connectTimeoutMs;
    private final long maxResponseBytes;
    private final Supplier<RuntimeException> tooLarge;
    private final Map<EventLoop, Pool> pools = new ConcurrentHashMap<>();

    LoopConnections(LoopResources loopResources, SocketAddress address, int maxConnections, long maxIdleTimeMs,
                    int connectTimeoutMs, long maxResponseBytes, Supplier<RuntimeException> tooLarge) {
        this.loopResources = loopResources;
        this.address = address;
        this.host = address instanceof InetSocketAddress inet ? hostHeader(inet) : address.toString();
        this.maxConnections = maxConnections;
        this.maxIdleNanos = maxIdleTimeMs * 1_000_000L;
        this.connectTimeoutMs = connectTimeoutMs;
        this.maxResponseBytes = maxResponseBytes;
        this.tooLarge = tooLarge;
    }

    private static String hostHeader(InetSocketAddress address) {
        String name = address.getHostString();
        String host = name.indexOf(':') >= 0 ? "[" + name + "]" : name;
        return host + ":" + address.getPort();
    }

    /**
     * Posts a body on the current thread's event loop.
     *
     * @param headers adds the request's headers (the host and content length are set)
     * @return the response, or null if the current thread is no event loop
     */
    @Nullable Mono<Response> post(String path, byte[] body, Consumer<HttpHeaders> headers) {
        if (!(ThreadExecutorMap.currentExecutor() instanceof EventLoop loop)) {
            return null;
        }
        Pool pool = pools.get(loop);
        Pool used = pool != null ? pool : pools.computeIfAbsent(loop, Pool::new);
        return Mono.create(sink -> {
            Exchange exchange = new Exchange(used, path, body, headers, sink);
            sink.onCancel(exchange::cancel);
            if (loop.inEventLoop()) {
                used.acquire(exchange);
            } else {
                loop.execute(() -> used.acquire(exchange));
            }
        });
    }

    /** The connections of one event loop; used on that loop only. */
    private final class Pool {
        private final EventLoop loop;
        private final Bootstrap bootstrap;
        /** Idle connections, the most recently used last. */
        private final ArrayDeque<Connection> idle = new ArrayDeque<>();
        private final ArrayDeque<Exchange> waiting = new ArrayDeque<>();
        private int open;

        Pool(EventLoop loop) {
            this.loop = loop;
            this.bootstrap = new Bootstrap()
                .group(loop)
                .channel(loopResources.onChannelClass(SocketChannel.class, loop))
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel channel) {
                        channel.pipeline().addLast(new HttpClientCodec());
                    }
                });
        }

        void acquire(Exchange exchange) {
            long now = System.nanoTime();
            Connection connection;
            while ((connection = idle.pollLast()) != null) {
                if (connection.channel.isActive() && now - connection.idleSince < maxIdleNanos) {
                    exchange.start(connection, true);
                    return;
                }
                connection.channel.close();
            }
            if (open < maxConnections) {
                connect(exchange);
            } else {
                waiting.addLast(exchange);
            }
        }

        private void connect(Exchange exchange) {
            open++;
            bootstrap.connect(address).addListener(future -> {
                Channel channel = ((io.netty.channel.ChannelFuture) future).channel();
                if (!future.isSuccess()) {
                    open--;
                    exchange.fail(future.cause());
                    next();
                    return;
                }
                Connection created = new Connection(this, channel);
                channel.pipeline().addLast(created);
                channel.closeFuture().addListener(closed -> {
                    open--;
                    idle.remove(created);
                    next();
                });
                exchange.start(created, false);
            });
        }

        /** Gives a free connection to the next waiting call, or keeps it idle. */
        void release(Connection connection) {
            Exchange exchange;
            while ((exchange = waiting.pollFirst()) != null) {
                if (!exchange.cancelled) {
                    exchange.start(connection, true);
                    return;
                }
            }
            connection.idleSince = System.nanoTime();
            idle.addLast(connection);
        }

        /** Starts the next waiting call once a connection was closed. */
        private void next() {
            Exchange exchange;
            while (open < maxConnections && (exchange = waiting.pollFirst()) != null) {
                if (!exchange.cancelled) {
                    acquire(exchange);
                }
            }
        }
    }

    /** One connection: reads the response of its current exchange. */
    private final class Connection extends ChannelInboundHandlerAdapter {
        private final Pool pool;
        private final Channel channel;
        private @Nullable Exchange current;
        private long idleSince;
        private int status;
        private boolean keepAlive;
        private byte[] body = EMPTY;
        private int length;

        Connection(Pool pool, Channel channel) {
            this.pool = pool;
            this.channel = channel;
        }

        void send(Exchange exchange) {
            current = exchange;
            status = 0;
            length = 0;
            body = EMPTY;
            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, exchange.path,
                Unpooled.wrappedBuffer(exchange.body));
            HttpHeaders headers = request.headers();
            headers.set(HttpHeaderNames.HOST, host);
            exchange.headers.accept(headers);
            headers.setInt(HttpHeaderNames.CONTENT_LENGTH, exchange.body.length);
            channel.writeAndFlush(request).addListener(future -> {
                if (!future.isSuccess()) {
                    failed(future.cause());
                }
            });
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            try {
                Exchange exchange = current;
                if (exchange == null) {
                    return;
                }
                if (msg instanceof HttpResponse response) {
                    status = response.status().code();
                    keepAlive = HttpUtil.isKeepAlive(response);
                    long declared = HttpUtil.getContentLength(response, -1L);
                    if (declared > maxResponseBytes) {
                        failed(tooLarge.get());
                        return;
                    }
                    if (declared > 0) {
                        body = new byte[(int) declared];
                    }
                }
                if (msg instanceof HttpContent content) {
                    ByteBuf buffer = content.content();
                    int readable = buffer.readableBytes();
                    if ((long) length + readable > maxResponseBytes) {
                        failed(tooLarge.get());
                        return;
                    }
                    if (length + readable > body.length) {
                        body = Arrays.copyOf(body, Math.max(length + readable, body.length * 2));
                    }
                    buffer.readBytes(body, length, readable);
                    length += readable;
                    if (msg instanceof LastHttpContent) {
                        byte[] received = length == body.length ? body : Arrays.copyOf(body, length);
                        int received0 = status;
                        current = null;
                        body = EMPTY;
                        if (keepAlive) {
                            pool.release(this);
                        } else {
                            channel.close();
                        }
                        exchange.succeed(new Response(received0, received));
                    }
                }
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (current != null) {
                failed(new IOException("Connection closed before the subgraph's response was received"));
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            failed(cause);
        }

        /** Fails the current exchange and closes the connection: its state is unknown. */
        void failed(Throwable cause) {
            Exchange exchange = current;
            current = null;
            channel.close();
            if (exchange != null) {
                // A reused connection the server closed while it was idle: once more, on another connection
                if (exchange.reused && status == 0 && !exchange.retried && cause instanceof IOException) {
                    exchange.retried = true;
                    pool.acquire(exchange);
                } else {
                    exchange.fail(cause);
                }
            }
        }

        /** Drops the current exchange, cancelled by its caller. */
        void abandon(Exchange exchange) {
            if (current == exchange) {
                current = null;
                channel.close();
            }
        }
    }

    /** One call. */
    private static final class Exchange {
        private final Pool pool;
        private final String path;
        private final byte[] body;
        private final Consumer<HttpHeaders> headers;
        private final MonoSink<Response> sink;
        private @Nullable Connection connection;
        private boolean reused;
        private boolean retried;
        private volatile boolean cancelled;

        Exchange(Pool pool, String path, byte[] body, Consumer<HttpHeaders> headers, MonoSink<Response> sink) {
            this.pool = pool;
            this.path = path;
            this.body = body;
            this.headers = headers;
            this.sink = sink;
        }

        void start(Connection connection, boolean reused) {
            if (cancelled) {
                pool.release(connection);
                return;
            }
            this.connection = connection;
            this.reused = reused;
            connection.send(this);
        }

        void succeed(Response response) {
            sink.success(response);
        }

        void fail(Throwable cause) {
            sink.error(cause);
        }

        void cancel() {
            cancelled = true;
            if (pool.loop.inEventLoop()) {
                abandon();
            } else {
                pool.loop.execute(this::abandon);
            }
        }

        private void abandon() {
            pool.waiting.remove(this);
            Connection current = connection;
            if (current != null) {
                current.abandon(this);
            }
        }
    }
}
