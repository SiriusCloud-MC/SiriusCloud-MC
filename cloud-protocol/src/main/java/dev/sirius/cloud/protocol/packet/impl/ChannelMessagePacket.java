package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

/**
 * A published message, travelling in both directions.
 *
 * <p>Service to node it means "send this to everyone"; node to service it means
 * "somebody sent you this". The node is the broker, which is what makes a
 * cloud-wide channel work without every service knowing about every other one.
 *
 * <p>{@link #sourceService} is set by the <em>node</em> from the authenticated
 * connection, never trusted from the sender: a service must not be able to
 * publish under another's name, and the field is what tells a subscriber to
 * skip its own message.
 */
public final class ChannelMessagePacket extends Packet {

    private String channel;
    private String payload;
    private String sourceService;

    public ChannelMessagePacket() {
    }

    public ChannelMessagePacket(String channel, String payload, String sourceService) {
        this.channel = channel;
        this.payload = payload;
        this.sourceService = sourceService;
    }

    public String channel() {
        return channel;
    }

    public String payload() {
        return payload == null ? "" : payload;
    }

    public String sourceService() {
        return sourceService == null ? "" : sourceService;
    }

    public void sourceService(String sourceService) {
        this.sourceService = sourceService;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeString(channel == null ? "" : channel)
                .writeString(payload())
                .writeString(sourceService());
    }

    @Override
    public void read(DataBuf buf) {
        this.channel = buf.readString();
        this.payload = buf.readString();
        this.sourceService = buf.readString();
    }
}
