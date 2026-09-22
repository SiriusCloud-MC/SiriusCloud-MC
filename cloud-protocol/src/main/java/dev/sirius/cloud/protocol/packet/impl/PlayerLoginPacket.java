package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.api.player.CloudPlayer;
import dev.sirius.cloud.protocol.packet.Packet;

/** Proxy to node: a player joined the cloud. */
public final class PlayerLoginPacket extends Packet {

    private CloudPlayer player;

    public PlayerLoginPacket() {
    }

    public PlayerLoginPacket(CloudPlayer player) {
        this.player = player;
    }

    public CloudPlayer player() {
        return player;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeObject(player);
    }

    @Override
    public void read(DataBuf buf) {
        this.player = buf.readObject(CloudPlayer.class);
    }
}
