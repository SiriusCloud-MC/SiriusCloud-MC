package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

/** Query: ask the node to start a service of a group. */
public final class ServiceStartRequestPacket extends Packet {

    private String groupName;

    public ServiceStartRequestPacket() {
    }

    public ServiceStartRequestPacket(String groupName) {
        this.groupName = groupName;
    }

    public String groupName() {
        return groupName;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeString(groupName);
    }

    @Override
    public void read(DataBuf buf) {
        this.groupName = buf.readString();
    }
}
