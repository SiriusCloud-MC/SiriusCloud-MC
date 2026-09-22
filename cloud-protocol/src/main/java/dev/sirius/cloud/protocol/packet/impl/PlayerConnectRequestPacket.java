package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

import java.util.UUID;

/**
 * Node to proxy: move this player to that service.
 *
 * <p>The node resolves a group to a concrete service before sending, so the
 * proxy never has to know what groups are or how to balance between them.
 */
public final class PlayerConnectRequestPacket extends Packet {

    private UUID playerId;
    private String serviceName;

    public PlayerConnectRequestPacket() {
    }

    public PlayerConnectRequestPacket(UUID playerId, String serviceName) {
        this.playerId = playerId;
        this.serviceName = serviceName;
    }

    public UUID playerId() {
        return playerId;
    }

    public String serviceName() {
        return serviceName;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeUniqueId(playerId).writeString(serviceName);
    }

    @Override
    public void read(DataBuf buf) {
        this.playerId = buf.readUniqueId();
        this.serviceName = buf.readString();
    }
}
