package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.api.service.ServiceState;
import dev.sirius.cloud.protocol.packet.Packet;

import java.util.UUID;

/** Wrapper to node: a service changed lifecycle state. */
public final class ServiceStateUpdatePacket extends Packet {

    private UUID serviceId;
    private ServiceState state;

    /** Process exit code, or -1 while the process is still alive. */
    private int exitCode;

    public ServiceStateUpdatePacket() {
    }

    public ServiceStateUpdatePacket(UUID serviceId, ServiceState state, int exitCode) {
        this.serviceId = serviceId;
        this.state = state;
        this.exitCode = exitCode;
    }

    public UUID serviceId() {
        return serviceId;
    }

    public ServiceState state() {
        return state;
    }

    public int exitCode() {
        return exitCode;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeUniqueId(serviceId).writeEnum(state).writeInt(exitCode);
    }

    @Override
    public void read(DataBuf buf) {
        this.serviceId = buf.readUniqueId();
        this.state = buf.readEnum(ServiceState.class);
        this.exitCode = buf.readInt();
    }
}
