package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

import java.util.UUID;

/**
 * Node to wrapper: shut a service down.
 *
 * <p>{@code force} skips the graceful stdin shutdown. It exists for services
 * that have stopped responding, and is never the default — a forced stop on
 * Windows is an immediate {@code TerminateProcess} with no chance to save.
 */
public final class ServiceStopPacket extends Packet {

    private UUID serviceId;
    private boolean force;

    public ServiceStopPacket() {
    }

    public ServiceStopPacket(UUID serviceId, boolean force) {
        this.serviceId = serviceId;
        this.force = force;
    }

    public UUID serviceId() {
        return serviceId;
    }

    public boolean force() {
        return force;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeUniqueId(serviceId).writeBoolean(force);
    }

    @Override
    public void read(DataBuf buf) {
        this.serviceId = buf.readUniqueId();
        this.force = buf.readBoolean();
    }
}
