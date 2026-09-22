package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.protocol.packet.Packet;

/** Reply to {@link ServiceStartRequestPacket}. */
public final class ServiceStartResponsePacket extends Packet {

    private boolean success;
    private String message;
    private ServiceInfo service;

    public ServiceStartResponsePacket() {
    }

    public ServiceStartResponsePacket(boolean success, String message, ServiceInfo service) {
        this.success = success;
        this.message = message;
        this.service = service;
    }

    public boolean success() {
        return success;
    }

    public String message() {
        return message;
    }

    public ServiceInfo service() {
        return service;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeBoolean(success)
                .writeString(message == null ? "" : message)
                .writeNullable(service, DataBuf::writeObject);
    }

    @Override
    public void read(DataBuf buf) {
        this.success = buf.readBoolean();
        this.message = buf.readString();
        this.service = buf.readNullable(b -> b.readObject(ServiceInfo.class));
    }
}
