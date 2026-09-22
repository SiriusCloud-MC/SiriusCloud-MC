package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

import java.util.UUID;

/**
 * Node to proxy: show this message to a player, or to everyone.
 *
 * <p>A null {@link #playerId()} means broadcast, and the node sends the packet
 * to every proxy. One packet rather than two because the proxy-side handling is
 * identical apart from which players it iterates.
 */
public final class PlayerMessagePacket extends Packet {

    private UUID playerId;
    private String message;

    public PlayerMessagePacket() {
    }

    public PlayerMessagePacket(UUID playerId, String message) {
        this.playerId = playerId;
        this.message = message;
    }

    public UUID playerId() {
        return playerId;
    }

    public boolean isBroadcast() {
        return playerId == null;
    }

    public String message() {
        return message;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeNullable(playerId, DataBuf::writeUniqueId).writeString(message);
    }

    @Override
    public void read(DataBuf buf) {
        this.playerId = buf.readNullable(DataBuf::readUniqueId);
        this.message = buf.readString();
    }
}
