package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.api.group.ServiceGroup;
import dev.sirius.cloud.protocol.packet.Packet;

import java.util.List;

/** Reply to {@link GroupListRequestPacket}. */
public final class GroupListResponsePacket extends Packet {

    private List<ServiceGroup> groups;

    public GroupListResponsePacket() {
    }

    public GroupListResponsePacket(List<ServiceGroup> groups) {
        this.groups = groups;
    }

    public List<ServiceGroup> groups() {
        return groups == null ? List.of() : groups;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeCollection(groups(), DataBuf::writeObject);
    }

    @Override
    public void read(DataBuf buf) {
        this.groups = buf.readList(b -> b.readObject(ServiceGroup.class));
    }
}
