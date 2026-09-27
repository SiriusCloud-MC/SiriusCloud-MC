package dev.sirius.cloud.protocol.connection;

import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.protocol.packet.Packet;
import dev.sirius.cloud.protocol.packet.PacketRegistry;
import dev.sirius.cloud.protocol.packet.impl.HandshakeResponsePacket;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Outbound connection to the node, used by wrappers, services and tools.
 *
 * <p>Reconnects on its own with a capped backoff. A wrapper losing the node
 * must not take its running services down with it — they keep playing, and the
 * wrapper re-registers them when the node comes back.
 *
 * <p>In a cluster it knows every node, and finds the leader by itself: it
 * works through the addresses in turn, follows a follower's redirect to the
 * leader, waits out an election, and refuses a node whose term is older than
 * one it has already seen - a leader that has been replaced but has not
 * noticed yet. None of that reaches the handler, which only ever sees the
 * leader accepting it or a real refusal.
 */
public final class NetworkClient implements AutoCloseable {

    private static final CloudLogger LOGGER = CloudLogger.of(NetworkClient.class);

    private static final int RECONNECT_DELAY_SECONDS = 3;
    /** Between addresses in one pass: the next node is worth trying straight away. */
    private static final int NEXT_ENDPOINT_DELAY_MILLIS = 250;
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;

    private final PacketRegistry registry;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean reconnecting = new AtomicBoolean(true);

    private EventLoopGroup group;
    private volatile Channel channel;

    private final LinkedHashSet<String> endpoints = new LinkedHashSet<>();
    private int cursor;
    /** Tried in this pass without success; a full pass waits the long delay. */
    private int failedInPass;
    /** A follower's pointer to the leader, used once for the next attempt. */
    private volatile String redirect;
    private volatile long highestTerm;
    /** Reconnect attempts at debug level, for connections whose absence is reported elsewhere. */
    private volatile boolean quiet;

    private PacketHandler handler;
    private Consumer<List<String>> endpointListener = endpoints -> {
    };
    private Consumer<Long> termListener = term -> {
    };

    public NetworkClient(PacketRegistry registry) {
        this.registry = registry;
    }

    /** Called whenever the node addresses change, so they can be remembered across restarts. */
    public void onEndpoints(Consumer<List<String>> listener) {
        this.endpointListener = listener;
    }

    /** Called when a newer leader term is seen, so it can be remembered across restarts. */
    public void onTerm(Consumer<Long> listener) {
        this.termListener = listener;
    }

    /** The highest leader term seen before this process started, if one was remembered. */
    public void highestTerm(long term) {
        this.highestTerm = Math.max(this.highestTerm, term);
    }

    public long highestTerm() {
        return highestTerm;
    }

    public void quiet(boolean quiet) {
        this.quiet = quiet;
    }

    public synchronized List<String> endpoints() {
        return List.copyOf(endpoints);
    }

    /**
     * Starts connecting and keeps trying until {@link #close()} is called.
     *
     * @return a future completing on the first successful <em>TCP</em> connect;
     *         the handshake is the caller's job in {@code onConnect}
     */
    public ChannelFuture connect(String host, int port, PacketHandler handler) {
        return connect(List.of(endpoint(host, port)), handler);
    }

    /** As {@link #connect(String, int, PacketHandler)}, with every node's {@code host:port} known so far. */
    public ChannelFuture connect(Collection<String> nodes, PacketHandler handler) {
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("No node address to connect to");
        }
        synchronized (this) {
            endpoints.addAll(nodes);
        }
        this.handler = new ClusterAwareHandler(handler);
        this.group = new NioEventLoopGroup(1, runnable -> {
            Thread thread = new Thread(runnable, "sirius-client");
            thread.setDaemon(true);
            return thread;
        });
        return doConnect();
    }

    public static String endpoint(String host, int port) {
        return (host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host) + ":" + port;
    }

    private ChannelFuture doConnect() {
        String target = nextTarget();
        InetSocketAddress address = parse(target);

        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel socketChannel) {
                        ProtocolPipeline.configure(socketChannel, registry, handler);
                    }
                });

        ChannelFuture future = bootstrap.connect(address);
        future.addListener(result -> {
            if (result.isSuccess()) {
                channel = future.channel();
                channel.closeFuture().addListener(closeResult -> scheduleReconnect());
            } else {
                failed(target, rootMessage(result.cause()));
                scheduleReconnect();
            }
        });
        return future;
    }

    private synchronized String nextTarget() {
        String preferred = redirect;
        if (preferred != null) {
            redirect = null;
            return preferred;
        }
        List<String> all = new ArrayList<>(endpoints);
        return all.get(Math.floorMod(cursor, all.size()));
    }

    /** Moves on to the next address; the current one did not work out. */
    private synchronized void advance() {
        cursor++;
        failedInPass++;
    }

    private void failed(String target, String reason) {
        boolean single;
        synchronized (this) {
            single = endpoints.size() == 1;
        }
        if (single && !quiet) {
            LOGGER.warn("Could not reach node at {} ({}), retrying in {}s", target, reason, RECONNECT_DELAY_SECONDS);
        } else {
            LOGGER.debug("Could not reach node at {} ({}), trying the next one", target, reason);
        }
        advance();
    }

    /**
     * Stops trying to reconnect, without closing what is already open.
     *
     * <p>For failures that cannot fix themselves — a rejected handshake is a
     * configuration error, not a blip — where retrying every few seconds only
     * produces an endless wall of identical errors.
     */
    public void stopReconnecting() {
        reconnecting.set(false);
    }

    private void scheduleReconnect() {
        if (closed.get() || !reconnecting.get() || group == null || group.isShuttingDown()) {
            return;
        }
        long delayMillis;
        synchronized (this) {
            if (redirect != null) {
                delayMillis = 0;
            } else if (failedInPass > 0 && failedInPass < endpoints.size()) {
                delayMillis = NEXT_ENDPOINT_DELAY_MILLIS;
            } else {
                failedInPass = 0;
                delayMillis = RECONNECT_DELAY_SECONDS * 1000L;
            }
        }
        group.schedule(this::doConnect, delayMillis, TimeUnit.MILLISECONDS);
    }

    private void learn(List<String> announced) {
        if (announced == null || announced.isEmpty()) {
            return;
        }
        List<String> snapshot;
        synchronized (this) {
            if (!endpoints.addAll(announced)) {
                return;
            }
            snapshot = List.copyOf(endpoints);
        }
        endpointListener.accept(snapshot);
    }

    public Optional<NetworkChannel> channel() {
        Channel current = this.channel;
        if (current == null || !current.isActive()) {
            return Optional.empty();
        }
        return Optional.ofNullable(current.attr(NetworkChannel.KEY).get());
    }

    public boolean isConnected() {
        return channel().isPresent();
    }

    /** Sends only if connected. Returns whether the packet went out. */
    public boolean send(Packet packet) {
        return channel().map(networkChannel -> {
            networkChannel.send(packet);
            return true;
        }).orElse(false);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Channel current = this.channel;
        if (current != null) {
            current.close().syncUninterruptibly();
        }
        if (group != null) {
            group.shutdownGracefully();
        }
    }

    static InetSocketAddress parse(String endpoint) {
        int colon = endpoint.lastIndexOf(':');
        if (colon <= 0 || colon == endpoint.length() - 1) {
            throw new IllegalArgumentException("Not host:port: " + endpoint);
        }
        String host = endpoint.substring(0, colon);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        return new InetSocketAddress(host, Integer.parseInt(endpoint.substring(colon + 1)));
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message;
    }

    /** Deals with the cluster's answers to a handshake, and passes everything else through. */
    private final class ClusterAwareHandler implements PacketHandler {

        private final PacketHandler delegate;

        ClusterAwareHandler(PacketHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onConnect(NetworkChannel channel) {
            delegate.onConnect(channel);
        }

        @Override
        public void onDisconnect(NetworkChannel channel) {
            delegate.onDisconnect(channel);
        }

        @Override
        public void onException(NetworkChannel channel, Throwable throwable) {
            delegate.onException(channel, throwable);
        }

        @Override
        public void onPacket(NetworkChannel channel, Packet packet) {
            if (packet instanceof HandshakeResponsePacket response) {
                learn(response.endpoints());
                if (!response.accepted() && !response.redirectTarget().isBlank()) {
                    LOGGER.debug("{}", response.message());
                    synchronized (NetworkClient.this) {
                        redirect = response.redirectTarget();
                        endpoints.add(response.redirectTarget());
                    }
                    channel.close();
                    return;
                }
                if (!response.accepted() && response.retry()) {
                    LOGGER.info("The cluster has no leader right now ({}); trying again", response.message());
                    advance();
                    channel.close();
                    return;
                }
                if (response.accepted() && response.term() >= 0) {
                    if (response.term() < highestTerm) {
                        // A leader that has been replaced and not noticed yet.
                        // Obeying it now could undo what the real one decides.
                        LOGGER.warn("Ignoring a node at term {}: term {} has already been seen", response.term(),
                                highestTerm);
                        advance();
                        channel.close();
                        return;
                    }
                    if (response.term() > highestTerm) {
                        highestTerm = response.term();
                        termListener.accept(response.term());
                    }
                    synchronized (NetworkClient.this) {
                        failedInPass = 0;
                    }
                }
            }
            delegate.onPacket(channel, packet);
        }
    }
}
