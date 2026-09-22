package dev.sirius.cloud.protocol.connection;

import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.protocol.packet.Packet;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.io.IOException;

/** Glues a Netty channel to a {@link PacketHandler} and a {@link NetworkChannel}. */
final class NetworkBridge extends SimpleChannelInboundHandler<Packet> {

    private static final CloudLogger LOGGER = CloudLogger.of(NetworkBridge.class);

    private final PacketHandler handler;

    private NetworkChannel networkChannel;

    NetworkBridge(PacketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        networkChannel = new NetworkChannel(ctx.channel());
        ctx.channel().attr(NetworkChannel.KEY).set(networkChannel);
        handler.onConnect(networkChannel);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (networkChannel != null) {
            networkChannel.failPendingQueries(new IOException("Connection closed"));
            handler.onDisconnect(networkChannel);
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Packet packet) {
        // Replies complete their future and go no further.
        if (networkChannel.completePendingQuery(packet)) {
            return;
        }
        try {
            handler.onPacket(networkChannel, packet);
        } catch (Exception exception) {
            LOGGER.error("Handler threw on " + packet.getClass().getSimpleName()
                    + " from " + networkChannel, exception);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof IOException) {
            // Peer went away mid-write. Normal during shutdown; not worth a stacktrace.
            LOGGER.debug("Connection error on {}: {}", networkChannel, cause.getMessage());
        } else {
            LOGGER.error("Unexpected error on " + networkChannel, cause);
        }
        if (networkChannel != null) {
            handler.onException(networkChannel, cause);
        }
        ctx.close();
    }
}
