package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.ConnectionType;
import dev.sirius.cloud.protocol.packet.Packet;

import java.util.UUID;

/**
 * First packet on every connection. Until the node answers with an accepting
 * {@link HandshakeResponsePacket}, nothing else is processed.
 */
public final class HandshakePacket extends Packet {

    private ConnectionType type;
    private String name;

    /** Shared secret for WRAPPER/API, one-time service token for SERVICE. */
    private String credential;

    /** Only set for SERVICE connections. */
    private UUID serviceId;

    private String platform;

    public HandshakePacket() {
    }

    public HandshakePacket(ConnectionType type, String name, String credential, UUID serviceId, String platform) {
        this.type = type;
        this.name = name;
        this.credential = credential;
        this.serviceId = serviceId;
        this.platform = platform;
    }

    public ConnectionType type() {
        return type;
    }

    public String name() {
        return name;
    }

    public String credential() {
        return credential;
    }

    public UUID serviceId() {
        return serviceId;
    }

    public String platform() {
        return platform;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeEnum(type)
                .writeString(name)
                .writeString(credential)
                .writeNullable(serviceId, DataBuf::writeUniqueId)
                .writeString(platform == null ? "unknown" : platform);
    }

    @Override
    public void read(DataBuf buf) {
        this.type = buf.readEnum(ConnectionType.class);
        this.name = buf.readString();
        this.credential = buf.readString();
        this.serviceId = buf.readNullable(DataBuf::readUniqueId);
        this.platform = buf.readString();
    }
}
