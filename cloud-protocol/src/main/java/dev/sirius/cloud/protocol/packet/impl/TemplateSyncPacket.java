package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

/**
 * Templates between wrappers and the node, in both directions: lists of
 * files and hashes, file contents in pieces, deletions.
 *
 * <p>A kind and a JSON body, like the cluster's messages, since both ends are
 * one class each and change together; {@link #data} carries file contents.
 */
public final class TemplateSyncPacket extends Packet {

    private String kind;
    private String body;
    private byte[] data;

    public TemplateSyncPacket() {
    }

    public TemplateSyncPacket(String kind, String body) {
        this(kind, body, new byte[0]);
    }

    public TemplateSyncPacket(String kind, String body, byte[] data) {
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
