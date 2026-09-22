package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.api.player.CloudPlayer;
import dev.sirius.cloud.protocol.packet.Packet;

import java.util.List;
import java.util.UUID;

/**
 * Proxy to node: everyone currently connected through this proxy.
 *
 * <p>Sent right after the handshake, and it <em>replaces</em> whatever the node
 * believed about this proxy rather than adding to it. That is what makes a node
 * restart survivable: players who were online throughout would otherwise be
 * invisible to the new node forever, since their login events are long past.
 * It equally clears out ghosts left by a proxy that died without saying goodbye.
 */
public final class PlayerSnapshotPacket extends Packet {

    private UUID proxyId;
    private List<CloudPlayer> players;

    public PlayerSnapshotPacket() {
    }

    public PlayerSnapshotPacket(UUID proxyId, List<CloudPlayer> players) {
        this.proxyId = proxyId;
        this.players = players;
    }

    public UUID proxyId() {
        return proxyId;
    }

    public List<CloudPlayer> players() {
        return players == null ? List.of() : players;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeUniqueId(proxyId).writeCollection(players(), DataBuf::writeObject);
    }

    @Override
    public void read(DataBuf buf) {
        this.proxyId = buf.readUniqueId();
        this.players = buf.readList(b -> b.readObject(CloudPlayer.class));
    }
}
