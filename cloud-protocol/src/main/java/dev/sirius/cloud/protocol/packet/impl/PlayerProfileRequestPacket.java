package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

import java.util.UUID;

/** Query: a player's profile, by UUID or by name. Exactly one is set. */
public final class PlayerProfileRequestPacket extends Packet {

    private UUID uniqueId;
    private String name;

    public PlayerProfileRequestPacket() {
    }

    public PlayerProfileRequestPacket(UUID uniqueId, String name) {
        this.uniqueId = uniqueId;
        this.name = name;
    }

    public UUID uniqueId() {
        return uniqueId;
    }

    public String name() {
        return name;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeNullable(uniqueId, DataBuf::writeUniqueId).writeNullable(name, DataBuf::writeString);
    }

    @Override
    public void read(DataBuf buf) {
        this.uniqueId = buf.readNullable(DataBuf::readUniqueId);
        this.name = buf.readNullable(DataBuf::readString);
    }
}
