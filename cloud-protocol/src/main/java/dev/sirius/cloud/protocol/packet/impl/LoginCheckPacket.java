package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Query, proxy to node: may this player join? Answered with a {@link LoginVerdictPacket}. */
public final class LoginCheckPacket extends Packet {

    private UUID playerId;
    private String name;
    private String address;
    private Map<String, Boolean> permissions;

    public LoginCheckPacket() {
    }

    public LoginCheckPacket(UUID playerId, String name, String address, Map<String, Boolean> permissions) {
        this.playerId = playerId;
        this.name = name;
        this.address = address;
        this.permissions = permissions;
    }

    public UUID playerId() {
        return playerId;
    }

    public String name() {
        return name;
    }

    public String address() {
        return address;
    }

    public Map<String, Boolean> permissions() {
        return permissions == null ? Map.of() : permissions;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeUniqueId(playerId).writeString(name).writeString(address == null ? "" : address);
        buf.writeVarInt(permissions().size());
        permissions().forEach((key, value) -> buf.writeString(key).writeBoolean(value));
    }

    @Override
    public void read(DataBuf buf) {
        this.playerId = buf.readUniqueId();
        this.name = buf.readString();
        this.address = buf.readString();
        int size = buf.readVarInt();
        this.permissions = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            permissions.put(buf.readString(), buf.readBoolean());
        }
    }
}
