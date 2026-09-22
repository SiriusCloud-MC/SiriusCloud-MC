package dev.sirius.cloud.protocol.codec;

import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.protocol.buffer.NettyDataBuf;
import dev.sirius.cloud.protocol.packet.Packet;
import dev.sirius.cloud.protocol.packet.PacketRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.util.List;

/** Turns a length-delimited frame back into a packet instance. */
public final class PacketDecoder extends MessageToMessageDecoder<ByteBuf> {

    private static final CloudLogger LOGGER = CloudLogger.of(PacketDecoder.class);

    private final PacketRegistry registry;

    public PacketDecoder(PacketRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        NettyDataBuf buf = new NettyDataBuf(in);
        int id = buf.readVarInt();

        Packet packet;
        try {
            packet = registry.create(id);
        } catch (IllegalArgumentException exception) {
            // An unknown id means the peer speaks a newer protocol. Skip the
            // frame rather than killing a connection that is otherwise healthy.
            LOGGER.warn("Discarding frame with unknown packet id 0x{} from {}",
                    Integer.toHexString(id), ctx.channel().remoteAddress());
            return;
        }

        if (buf.readBoolean()) {
            packet.queryId(buf.readUniqueId());
        }

        packet.read(buf);
        out.add(packet);
    }
}
