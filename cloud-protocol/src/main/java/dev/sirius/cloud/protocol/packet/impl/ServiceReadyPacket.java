package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

import java.util.UUID;

/**
 * Service to node: fully started and accepting players.
 *
 * <p>This is why the Paper plugin is part of the skeleton rather than a later
 * milestone. Without it the node would have to guess at readiness by scraping
 * "Done (x.xxs)" out of the log, which breaks on every format change and lies
 * about servers that boot but fail to bind.
 */
public final class ServiceReadyPacket extends Packet {

    private UUID serviceId;
    private int maxPlayers;
    private String version;

    public ServiceReadyPacket() {
    }

    public ServiceReadyPacket(UUID serviceId, int maxPlayers, String version) {
        this.serviceId = serviceId;
        this.maxPlayers = maxPlayers;
        this.version = version;
    }

    public UUID serviceId() {
        return serviceId;
    }

    public int maxPlayers() {
        return maxPlayers;
    }

    public String version() {
        return version;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeUniqueId(serviceId).writeInt(maxPlayers).writeString(version == null ? "" : version);
    }

    @Override
    public void read(DataBuf buf) {
        this.serviceId = buf.readUniqueId();
        this.maxPlayers = buf.readInt();
        this.version = buf.readString();
    }
}
