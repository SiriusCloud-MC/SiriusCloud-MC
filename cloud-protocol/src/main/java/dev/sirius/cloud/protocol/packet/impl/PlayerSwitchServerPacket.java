package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

import java.util.UUID;

/** Proxy to node: a player landed on a backend server. */
public final class PlayerSwitchServerPacket extends Packet {

    private UUID playerId;
    private UUID serverId;
    private String serverName;

    public PlayerSwitchServerPacket() {
    }

    public PlayerSwitchServerPacket(UUID playerId, UUID serverId, String serverName) {
        this.playerId = playerId;
        this.serverId = serverId;
        this.serverName = serverName;
    }

    public UUID playerId() {
        return playerId;
    }

    public UUID serverId() {
        return serverId;
    }

    public String serverName() {
        return serverName;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeUniqueId(playerId)
                .writeNullable(serverId, DataBuf::writeUniqueId)
                .writeString(serverName == null ? "" : serverName);
    }

    @Override
    public void read(DataBuf buf) {
        this.playerId = buf.readUniqueId();
        this.serverId = buf.readNullable(DataBuf::readUniqueId);
        this.serverName = buf.readString();
    }
}
