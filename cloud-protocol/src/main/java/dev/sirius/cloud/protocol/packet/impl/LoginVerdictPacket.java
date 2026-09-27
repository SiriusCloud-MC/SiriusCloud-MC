package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

/** Reply to {@link LoginCheckPacket}. The reason is MiniMessage, and only meaningful when refused. */
public final class LoginVerdictPacket extends Packet {

    private boolean allowed;
    private String reason;

    public LoginVerdictPacket() {
    }

    public LoginVerdictPacket(boolean allowed, String reason) {
        this.allowed = allowed;
        this.reason = reason;
    }

    public boolean allowed() {
        return allowed;
    }

    public String reason() {
        return reason == null ? "" : reason;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeBoolean(allowed).writeString(reason());
    }

    @Override
    public void read(DataBuf buf) {
        this.allowed = buf.readBoolean();
        this.reason = buf.readString();
    }
}
