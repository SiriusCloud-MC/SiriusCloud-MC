package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

/** The node's verdict on a handshake. A rejection is followed by a close. */
public final class HandshakeResponsePacket extends Packet {

    private boolean accepted;
    private String message;

    public HandshakeResponsePacket() {
    }

    public HandshakeResponsePacket(boolean accepted, String message) {
        this.accepted = accepted;
        this.message = message;
    }

    public boolean accepted() {
        return accepted;
    }

    public String message() {
        return message;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeBoolean(accepted).writeString(message == null ? "" : message);
    }

    @Override
    public void read(DataBuf buf) {
        this.accepted = buf.readBoolean();
        this.message = buf.readString();
    }
}
