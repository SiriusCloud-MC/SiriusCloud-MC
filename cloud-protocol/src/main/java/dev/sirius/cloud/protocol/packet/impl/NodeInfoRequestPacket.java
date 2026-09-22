package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

/** Query: describe the control plane and the machines attached to it. */
public final class NodeInfoRequestPacket extends Packet {

    @Override
    public void write(DataBuf buf) {
    }

    @Override
    public void read(DataBuf buf) {
    }
}
