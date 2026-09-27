package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

/**
 * Everything nodes say to each other: votes, heartbeats, replication.
 *
 * <p>One packet with a kind and a JSON body rather than a packet class per
 * message, because the cluster's messages change together and are only ever
 * read by one class on each end. {@link #data} carries file contents, which
 * are not text and should not be inflated by encoding them into the JSON.
 */
public final class ClusterPacket extends Packet {

    private String kind;
    private String body;
    private byte[] data;

    public ClusterPacket() {
    }

    public ClusterPacket(String kind, String body) {
        this(kind, body, new byte[0]);
    }

    public ClusterPacket(String kind, String body, byte[] data) {
        this.kind = kind;
        this.body = body;
        this.data = data;
    }

    public String kind() {
        return kind;
    }

    public String body() {
        return body;
    }

    public byte[] data() {
        return data;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeString(kind).writeString(body).writeBytes(data);
    }

    @Override
    public void read(DataBuf buf) {
        this.kind = buf.readString();
        this.body = buf.readString();
        this.data = buf.readBytes();
    }
}
