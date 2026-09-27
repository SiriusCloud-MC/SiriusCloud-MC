package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

import java.util.List;

/**
 * Service to node: every channel this service wants messages on.
 *
 * <p>Always the complete set, replacing whatever the node held. A service that
 * reconnects therefore cannot be left subscribed to something it dropped while
 * offline, and the node never has to reconcile a stream of deltas.
 */
public final class ChannelSubscriptionsPacket extends Packet {

    private List<String> channels;

    public ChannelSubscriptionsPacket() {
    }

    public ChannelSubscriptionsPacket(List<String> channels) {
        this.channels = channels;
    }

    public List<String> channels() {
        return channels == null ? List.of() : channels;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeCollection(channels(), DataBuf::writeString);
    }

    @Override
    public void read(DataBuf buf) {
        this.channels = buf.readList(DataBuf::readString);
    }
}
