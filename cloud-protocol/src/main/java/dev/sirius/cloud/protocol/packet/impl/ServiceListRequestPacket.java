package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

/** Query: list services, optionally filtered to one group. */
public final class ServiceListRequestPacket extends Packet {

    private String groupFilter;

    public ServiceListRequestPacket() {
    }

    public ServiceListRequestPacket(String groupFilter) {
        this.groupFilter = groupFilter;
    }

    public String groupFilter() {
        return groupFilter;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeNullable(groupFilter, DataBuf::writeString);
    }

    @Override
    public void read(DataBuf buf) {
        this.groupFilter = buf.readNullable(DataBuf::readString);
    }
}
