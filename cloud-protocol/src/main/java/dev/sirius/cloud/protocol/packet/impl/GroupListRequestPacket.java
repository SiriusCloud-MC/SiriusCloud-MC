package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

/** Query: list every configured group. */
public final class GroupListRequestPacket extends Packet {

    @Override
    public void write(DataBuf buf) {
    }

    @Override
    public void read(DataBuf buf) {
    }
}
