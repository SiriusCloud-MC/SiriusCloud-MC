package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

import java.util.UUID;

/** Wrapper to node: one line of a service's console output. */
public final class ConsoleLinePacket extends Packet {

    private UUID serviceId;
    private String serviceName;
    private String line;

    public ConsoleLinePacket() {
    }

    public ConsoleLinePacket(UUID serviceId, String serviceName, String line) {
        this.serviceId = serviceId;
        this.serviceName = serviceName;
        this.line = line;
    }

    public UUID serviceId() {
        return serviceId;
    }

    public String serviceName() {
        return serviceName;
    }

    public String line() {
        return line;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeUniqueId(serviceId).writeString(serviceName).writeString(line);
    }

    @Override
    public void read(DataBuf buf) {
        this.serviceId = buf.readUniqueId();
        this.serviceName = buf.readString();
        this.line = buf.readString();
    }
}
