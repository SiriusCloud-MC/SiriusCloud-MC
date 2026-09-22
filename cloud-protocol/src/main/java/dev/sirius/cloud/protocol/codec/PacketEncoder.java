package dev.sirius.cloud.protocol.codec;

import dev.sirius.cloud.protocol.buffer.NettyDataBuf;
import dev.sirius.cloud.protocol.packet.Packet;
import dev.sirius.cloud.protocol.packet.PacketRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import java.util.UUID;

/**
 * Serialises a packet body. A {@code LengthFieldPrepender} sits in front of
 * this in the pipeline and adds the frame length.
 */
public final class PacketEncoder extends MessageToByteEncoder<Packet> {

    private final PacketRegistry registry;

    public PacketEncoder(PacketRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, Packet packet, ByteBuf out) {
        NettyDataBuf buf = new NettyDataBuf(out);
        buf.writeVarInt(registry.idOf(packet));

        UUID queryId = packet.queryId();
        buf.writeBoolean(queryId != null);
        if (queryId != null) {
            buf.writeUniqueId(queryId);
        }

        packet.write(buf);
    }
}
