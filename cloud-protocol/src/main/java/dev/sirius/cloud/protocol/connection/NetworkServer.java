package dev.sirius.cloud.protocol.connection;

import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.protocol.packet.PacketRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.net.InetSocketAddress;

/**
 * The node's listener. Wrappers, services and API clients all dial in here.
 *
 * <p>Deliberately NIO rather than native epoll: epoll needs a
 * classifier-qualified native artifact per OS and architecture, and the
 * throughput of a control plane that moves a few hundred small packets a second
 * is nowhere near where that would matter. One transport, both platforms.
 */
public final class NetworkServer implements AutoCloseable {

    private static final CloudLogger LOGGER = CloudLogger.of(NetworkServer.class);

    private final PacketRegistry registry;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public NetworkServer(PacketRegistry registry) {
        this.registry = registry;
    }

    public void start(String host, int port, PacketHandler handler) throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1, threadFactory("sirius-net-boss"));
        workerGroup = new NioEventLoopGroup(0, threadFactory("sirius-net-worker"));

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 128)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        ProtocolPipeline.configure(channel, registry, handler);
                    }
                });

        serverChannel = bootstrap.bind(new InetSocketAddress(host, port)).sync().channel();
        LOGGER.info("Listening on {}:{} ({} packet types registered)", host, port, registry.size());
    }

    @Override
    public void close() {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }

    private static java.util.concurrent.ThreadFactory threadFactory(String prefix) {
        return runnable -> {
            Thread thread = new Thread(runnable, prefix);
            thread.setDaemon(true);
            return thread;
        };
    }
}
