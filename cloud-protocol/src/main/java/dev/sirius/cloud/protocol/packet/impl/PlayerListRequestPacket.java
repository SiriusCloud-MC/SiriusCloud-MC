package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

/** Query: every player online, optionally narrowed to one service. */
public final class PlayerListRequestPacket extends Packet {

    private String serviceFilter;

    public PlayerListRequestPacket() {
    }

    public PlayerListRequestPacket(String serviceFilter) {
        this.serviceFilter = serviceFilter;
    }

    public String serviceFilter() {
        return serviceFilter;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeNullable(serviceFilter, DataBuf::writeString);
    }

    @Override
    public void read(DataBuf buf) {
        this.serviceFilter = buf.readNullable(DataBuf::readString);
    }
}
