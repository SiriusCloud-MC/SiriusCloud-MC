package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Proxy to node: a player ran a network command.
 *
 * <p>As a query it asks for tab completions instead of running it. The
 * permission answers ride along because the node cannot test permissions
 * itself - only the proxy knows what a player holds.
 */
public final class NetworkCommandPacket extends Packet {

    private UUID playerId;
    private String command;
    private List<String> arguments;
    private boolean suggest;
    private Map<String, Boolean> permissions;

    public NetworkCommandPacket() {
    }

    public NetworkCommandPacket(UUID playerId, String command, List<String> arguments,
                                boolean suggest, Map<String, Boolean> permissions) {
        this.playerId = playerId;
        this.command = command;
        this.arguments = arguments;
        this.suggest = suggest;
        this.permissions = permissions;
    }

    public UUID playerId() {
        return playerId;
    }

    public String command() {
        return command;
    }

    public List<String> arguments() {
        return arguments == null ? List.of() : arguments;
    }

    public boolean suggest() {
        return suggest;
    }

    public Map<String, Boolean> permissions() {
        return permissions == null ? Map.of() : permissions;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeUniqueId(playerId)
                .writeString(command)
                .writeCollection(arguments(), DataBuf::writeString)
                .writeBoolean(suggest);
        buf.writeVarInt(permissions().size());
        permissions().forEach((key, value) -> buf.writeString(key).writeBoolean(value));
    }

    @Override
    public void read(DataBuf buf) {
        this.playerId = buf.readUniqueId();
        this.command = buf.readString();
        this.arguments = buf.readList(DataBuf::readString);
        this.suggest = buf.readBoolean();
        int size = buf.readVarInt();
        this.permissions = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            permissions.put(buf.readString(), buf.readBoolean());
        }
    }
}
