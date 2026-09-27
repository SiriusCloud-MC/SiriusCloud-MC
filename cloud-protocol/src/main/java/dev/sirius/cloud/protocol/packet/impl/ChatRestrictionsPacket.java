package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

import java.util.List;
import java.util.UUID;

/**
 * Node to server: every player who may not chat, and why.
 *
 * <p>The whole set each time. It is small, and a server that missed one change
 * while reconnecting is corrected by the next rather than muting the wrong
 * people until it restarts.
 */
public final class ChatRestrictionsPacket extends Packet {

    /** @param untilMillis epoch millis, or 0 for indefinitely */
    public record Restriction(UUID playerId, long untilMillis, String reason) {
    }

    private List<Restriction> restrictions;

    public ChatRestrictionsPacket() {
    }

    public ChatRestrictionsPacket(List<Restriction> restrictions) {
        this.restrictions = restrictions;
    }

    public List<Restriction> restrictions() {
        return restrictions == null ? List.of() : restrictions;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeCollection(restrictions(), (out, restriction) -> out
                .writeUniqueId(restriction.playerId())
                .writeLong(restriction.untilMillis())
                .writeString(restriction.reason() == null ? "" : restriction.reason()));
    }

    @Override
    public void read(DataBuf buf) {
        this.restrictions = buf.readList(in -> new Restriction(in.readUniqueId(), in.readLong(), in.readString()));
    }
}
