package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

import java.util.UUID;

/** Node to proxy: disconnect this player. */
public final class PlayerKickPacket extends Packet {

    private UUID playerId;
    private String reason;

    public PlayerKickPacket() {
    }

    public PlayerKickPacket(UUID playerId, String reason) {
        this.playerId = playerId;
        this.reason = reason;
    }

    public UUID playerId() {
        return playerId;
    }

    public String reason() {
        return reason;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeUniqueId(playerId).writeString(reason == null ? "" : reason);
    }

    @Override
    public void read(DataBuf buf) {
        this.playerId = buf.readUniqueId();
        this.reason = buf.readString();
    }
}
