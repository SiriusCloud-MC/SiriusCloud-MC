package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

import java.util.List;

/**
 * Node to proxy: every network command to expose, and the permissions login
 * filters need answered.
 *
 * <p>Always the whole set, replacing what the proxy had, so a module that was
 * unloaded takes its commands with it on the next sync.
 */
public final class NetworkCommandsPacket extends Packet {

    /** Mirrors the parts of {@code NetworkCommand} a proxy needs to register it. */
    public record Definition(String name, List<String> aliases, String permission,
                             String description, List<String> extraPermissions) {
    }

    private List<Definition> commands;
    private List<String> loginPermissions;

    public NetworkCommandsPacket() {
    }

    public NetworkCommandsPacket(List<Definition> commands, List<String> loginPermissions) {
        this.commands = commands;
        this.loginPermissions = loginPermissions;
    }

    public List<Definition> commands() {
        return commands == null ? List.of() : commands;
    }

    public List<String> loginPermissions() {
        return loginPermissions == null ? List.of() : loginPermissions;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeCollection(commands(), (out, definition) -> {
            out.writeString(definition.name());
            out.writeCollection(definition.aliases(), DataBuf::writeString);
            out.writeNullable(definition.permission(), DataBuf::writeString);
            out.writeString(definition.description() == null ? "" : definition.description());
            out.writeCollection(definition.extraPermissions(), DataBuf::writeString);
        });
        buf.writeCollection(loginPermissions(), DataBuf::writeString);
    }

    @Override
    public void read(DataBuf buf) {
        this.commands = buf.readList(in -> new Definition(
                in.readString(),
                in.readList(DataBuf::readString),
                in.readNullable(DataBuf::readString),
                in.readString(),
                in.readList(DataBuf::readString)));
        this.loginPermissions = buf.readList(DataBuf::readString);
    }
}
