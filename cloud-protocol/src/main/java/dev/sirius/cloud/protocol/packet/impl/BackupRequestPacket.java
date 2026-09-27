package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

import java.util.UUID;

/** Node to wrapper: back up this service's directory now. */
public final class BackupRequestPacket extends Packet {

    private UUID serviceId;
    private int keep;

    public BackupRequestPacket() {
    }

    public BackupRequestPacket(UUID serviceId, int keep) {
        this.serviceId = serviceId;
        this.keep = keep;
    }

    public UUID serviceId() {
        return serviceId;
    }

    /** How many backups of this service to keep, oldest removed first. */
    public int keep() {
        return keep;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeUniqueId(serviceId).writeInt(keep);
    }

    @Override
    public void read(DataBuf buf) {
        this.serviceId = buf.readUniqueId();
        this.keep = buf.readInt();
    }
}
