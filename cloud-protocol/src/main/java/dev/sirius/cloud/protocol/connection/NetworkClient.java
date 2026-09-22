package dev.sirius.cloud.protocol.connection;

import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.protocol.packet.Packet;
import dev.sirius.cloud.protocol.packet.PacketRegistry;
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
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Outbound connection to the node, used by wrappers, services and tools.
 *
 * <p>Reconnects on its own with a capped backoff. A wrapper losing the node
 * must not take its running services down with it — they keep playing, and the
 * wrapper re-registers them when the node comes back.
 */
public final class NetworkClient implements AutoCloseable {

    private static final CloudLogger LOGGER = CloudLogger.of(NetworkClient.class);

    private static final int RECONNECT_DELAY_SECONDS = 3;
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;

    private final PacketRegistry registry;
    private final AtomicBoolean closed = new AtomicBoolean();

    private EventLoopGroup group;
    private volatile Channel channel;

    private String host;
    private int port;
    private PacketHandler handler;

    public NetworkClient(PacketRegistry registry) {
        this.registry = registry;
    }

    /**
     * Starts connecting and keeps trying until {@link #close()} is called.
     *
     * @return a future completing on the first successful <em>TCP</em> connect;
     *         the handshake is the caller's job in {@code onConnect}
     */
    public ChannelFuture connect(String host, int port, PacketHandler handler) {
        this.host = host;
        this.port = port;
        this.handler = handler;
        this.group = new NioEventLoopGroup(1, runnable -> {
            Thread thread = new Thread(runnable, "sirius-client");
            thread.setDaemon(true);
            return thread;
        });
        return doConnect();
    }

    private ChannelFuture doConnect() {
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

        ChannelFuture future = bootstrap.connect(new InetSocketAddress(host, port));
        future.addListener(result -> {
            if (result.isSuccess()) {
                channel = future.channel();
                channel.closeFuture().addListener(closeResult -> scheduleReconnect());
            } else {
                LOGGER.warn("Could not reach node at {}:{} ({}), retrying in {}s",
                        host, port, rootMessage(result.cause()), RECONNECT_DELAY_SECONDS);
                scheduleReconnect();
            }
        });
        return future;
    }

    private void scheduleReconnect() {
        if (closed.get() || group == null || group.isShuttingDown()) {
            return;
        }
        group.schedule(this::doConnect, RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
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

    private static String rootMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message;
    }
}
