package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

import java.util.List;

/**
 * The node's verdict on a handshake. A rejection is followed by a close.
 *
 * <p>In a cluster it also carries where to go. An accepting leader names its
 * term and every node's address, so a client can find the next leader when
 * this one goes. A node that is not the leader rejects with {@link #redirect}
 * set to the leader, or with {@link #retry} while an election is under way;
 * clients act on both themselves rather than treating them as a refusal.
 */
public final class HandshakeResponsePacket extends Packet {

    private boolean accepted;
    private String message;

    /**
     * The leader's term. A client refuses a node whose term is older than one
     * it has already seen. {@link #STANDALONE} from a node that is not
     * clustered, where there is nothing to compare.
     */
    private long term = STANDALONE;

    public static final long STANDALONE = -1;

    /** Every node's client address, {@code host:port}, for failing over. */
    private List<String> endpoints = List.of();

    /** Set on a rejection by a follower: the leader's {@code host:port}. */
    private String redirect = "";

    /** Set on a rejection while there is no leader: try again shortly, elsewhere if need be. */
    private boolean retry;

    public HandshakeResponsePacket() {
    }

    public HandshakeResponsePacket(boolean accepted, String message) {
        this(accepted, message, STANDALONE, List.of(), "", false);
    }

    public HandshakeResponsePacket(boolean accepted, String message, long term, List<String> endpoints,
                                   String redirect, boolean retry) {
        this.accepted = accepted;
        this.message = message;
        this.term = term;
        this.endpoints = endpoints == null ? List.of() : endpoints;
        this.redirect = redirect == null ? "" : redirect;
        this.retry = retry;
    }

    public static HandshakeResponsePacket redirect(String leader, List<String> endpoints) {
        return new HandshakeResponsePacket(false, "not the leader; the leader is " + leader, STANDALONE, endpoints,
                leader, false);
    }

    public static HandshakeResponsePacket retryLater(String reason, List<String> endpoints) {
        return new HandshakeResponsePacket(false, reason, STANDALONE, endpoints, "", true);
    }

    public boolean accepted() {
        return accepted;
    }

    public String message() {
        return message;
    }

    public long term() {
        return term;
    }

    public List<String> endpoints() {
        return endpoints;
    }

    public String redirectTarget() {
        return redirect;
    }

    public boolean retry() {
        return retry;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeBoolean(accepted).writeString(message == null ? "" : message)
                .writeLong(term)
                .writeCollection(endpoints, DataBuf::writeString)
                .writeString(redirect)
                .writeBoolean(retry);
    }

    @Override
    public void read(DataBuf buf) {
        this.accepted = buf.readBoolean();
        this.message = buf.readString();
        this.term = buf.readLong();
        this.endpoints = buf.readList(DataBuf::readString);
        this.redirect = buf.readString();
        this.retry = buf.readBoolean();
    }
}
