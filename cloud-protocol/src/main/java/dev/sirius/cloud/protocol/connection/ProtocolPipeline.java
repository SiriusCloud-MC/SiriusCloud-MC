package dev.sirius.cloud.protocol.connection;

import dev.sirius.cloud.protocol.codec.PacketDecoder;
import dev.sirius.cloud.protocol.codec.PacketEncoder;
import dev.sirius.cloud.protocol.packet.PacketRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.timeout.ReadTimeoutHandler;

/**
 * The one place the pipeline is assembled, so client and server cannot drift.
 *
 * <pre>
 *   inbound  : frames -> packets -> bridge
 *   outbound : packets -> bytes -> length prefix
 * </pre>
 */
public final class ProtocolPipeline {

    /** 16 MiB. Generous for JSON payloads, small enough to bound an attack. */
    private static final int MAX_FRAME_LENGTH = 16 * 1024 * 1024;

    /** Dead-peer detection. Wrappers heartbeat well inside this. */
    private static final int READ_TIMEOUT_SECONDS = 45;

    private ProtocolPipeline() {
    }

    public static void configure(Channel channel, PacketRegistry registry, PacketHandler handler) {
        ChannelPipeline pipeline = channel.pipeline();

        pipeline.addLast("read-timeout", new ReadTimeoutHandler(READ_TIMEOUT_SECONDS));

        // Inbound: strip the 4-byte length prefix, then decode the body.
        pipeline.addLast("frame-decoder", new LengthFieldBasedFrameDecoder(MAX_FRAME_LENGTH, 0, 4, 0, 4));
        pipeline.addLast("packet-decoder", new PacketDecoder(registry));

        // Outbound handlers run tail-to-head, so the prepender must be added
        // before the encoder to end up wrapping its output.
        pipeline.addLast("frame-encoder", new LengthFieldPrepender(4));
        pipeline.addLast("packet-encoder", new PacketEncoder(registry));

        pipeline.addLast("bridge", new NetworkBridge(handler));
    }
}
