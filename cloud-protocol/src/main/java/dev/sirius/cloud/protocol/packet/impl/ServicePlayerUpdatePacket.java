package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

import java.util.UUID;

/**
 * Service to node: how many players are on, and how many fit.
 *
 * <p>Both numbers can move after startup. A proxy's capacity is the sum of the
 * servers currently registered with it, so it changes every time one starts or
 * stops; without this the node's view would keep reporting whatever the proxy
 * happened to advertise at boot, which is zero servers' worth.
 */
public final class ServicePlayerUpdatePacket extends Packet {

    private UUID serviceId;
    private int playerCount;
    private int maxPlayers;

    public ServicePlayerUpdatePacket() {
    }

    public ServicePlayerUpdatePacket(UUID serviceId, int playerCount, int maxPlayers) {
        this.serviceId = serviceId;
        this.playerCount = playerCount;
        this.maxPlayers = maxPlayers;
    }

    public UUID serviceId() {
        return serviceId;
    }

    public int playerCount() {
        return playerCount;
    }

    public int maxPlayers() {
        return maxPlayers;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeUniqueId(serviceId).writeInt(playerCount).writeInt(maxPlayers);
    }

    @Override
    public void read(DataBuf buf) {
        this.serviceId = buf.readUniqueId();
        this.playerCount = buf.readInt();
        this.maxPlayers = buf.readInt();
    }
}
