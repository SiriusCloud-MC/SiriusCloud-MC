package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.api.player.PlayerProfile;
import dev.sirius.cloud.protocol.packet.Packet;

/** Reply to {@link PlayerProfileRequestPacket}; the profile is null if unknown. */
public final class PlayerProfileResponsePacket extends Packet {

    private PlayerProfile profile;

    public PlayerProfileResponsePacket() {
    }

    public PlayerProfileResponsePacket(PlayerProfile profile) {
        this.profile = profile;
    }

    public PlayerProfile profile() {
        return profile;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeNullable(profile, DataBuf::writeObject);
    }

    @Override
    public void read(DataBuf buf) {
        this.profile = buf.readNullable(b -> b.readObject(PlayerProfile.class));
    }
}
