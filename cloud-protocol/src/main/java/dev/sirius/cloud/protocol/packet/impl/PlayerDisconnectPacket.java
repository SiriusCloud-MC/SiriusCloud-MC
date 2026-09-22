package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

import java.util.UUID;

/** Proxy to node: a player left the cloud. */
public final class PlayerDisconnectPacket extends Packet {

    private UUID playerId;

    public PlayerDisconnectPacket() {
    }

    public PlayerDisconnectPacket(UUID playerId) {
        this.playerId = playerId;
    }

    public UUID playerId() {
        return playerId;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeUniqueId(playerId);
    }

    @Override
    public void read(DataBuf buf) {
        this.playerId = buf.readUniqueId();
    }
}
