package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

import java.util.UUID;

/** Query, anyone to node: restrict or lift a player's chat. Answered with an acknowledgement. */
public final class ChatRestrictionRequestPacket extends Packet {

    private UUID playerId;
    private boolean lift;
    private long untilMillis;
    private String reason;

    public ChatRestrictionRequestPacket() {
    }

    public ChatRestrictionRequestPacket(UUID playerId, boolean lift, long untilMillis, String reason) {
        this.playerId = playerId;
        this.lift = lift;
        this.untilMillis = untilMillis;
        this.reason = reason;
    }

    public UUID playerId() {
        return playerId;
    }

    public boolean lift() {
        return lift;
    }

    public long untilMillis() {
        return untilMillis;
    }

    public String reason() {
        return reason == null ? "" : reason;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeUniqueId(playerId).writeBoolean(lift).writeLong(untilMillis).writeString(reason());
    }

    @Override
    public void read(DataBuf buf) {
        this.playerId = buf.readUniqueId();
        this.lift = buf.readBoolean();
        this.untilMillis = buf.readLong();
        this.reason = buf.readString();
    }
}
