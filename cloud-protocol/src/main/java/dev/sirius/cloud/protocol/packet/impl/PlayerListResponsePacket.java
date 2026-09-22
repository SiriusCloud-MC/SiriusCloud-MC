package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.api.player.CloudPlayer;
import dev.sirius.cloud.protocol.packet.Packet;

import java.util.List;

/** Reply to {@link PlayerListRequestPacket}. */
public final class PlayerListResponsePacket extends Packet {

    private List<CloudPlayer> players;

    public PlayerListResponsePacket() {
    }

    public PlayerListResponsePacket(List<CloudPlayer> players) {
        this.players = players;
    }

    public List<CloudPlayer> players() {
        return players == null ? List.of() : players;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeCollection(players(), DataBuf::writeObject);
    }

    @Override
    public void read(DataBuf buf) {
        this.players = buf.readList(b -> b.readObject(CloudPlayer.class));
    }
}
