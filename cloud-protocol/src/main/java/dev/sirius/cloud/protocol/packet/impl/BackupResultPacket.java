package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

import java.util.UUID;

/** Wrapper to node: how a backup went. */
public final class BackupResultPacket extends Packet {

    private UUID serviceId;
    private String serviceName;
    private boolean success;
    private String file;
    private long sizeBytes;
    private String message;

    public BackupResultPacket() {
    }

    public BackupResultPacket(UUID serviceId, String serviceName, boolean success,
                              String file, long sizeBytes, String message) {
        this.serviceId = serviceId;
        this.serviceName = serviceName;
        this.success = success;
        this.file = file;
        this.sizeBytes = sizeBytes;
        this.message = message;
    }

    public UUID serviceId() {
        return serviceId;
    }

    public String serviceName() {
        return serviceName;
    }

    public boolean success() {
        return success;
    }

    public String file() {
        return file == null ? "" : file;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    public String message() {
        return message == null ? "" : message;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeUniqueId(serviceId).writeString(serviceName).writeBoolean(success)
                .writeString(file()).writeLong(sizeBytes).writeString(message());
    }

    @Override
    public void read(DataBuf buf) {
        this.serviceId = buf.readUniqueId();
        this.serviceName = buf.readString();
        this.success = buf.readBoolean();
        this.file = buf.readString();
        this.sizeBytes = buf.readLong();
        this.message = buf.readString();
    }
}
