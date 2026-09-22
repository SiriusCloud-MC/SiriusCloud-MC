package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

import java.util.UUID;

/** Node to wrapper: write this command to a service's stdin. */
public final class ConsoleCommandPacket extends Packet {

    private UUID serviceId;
    private String command;

    public ConsoleCommandPacket() {
    }

    public ConsoleCommandPacket(UUID serviceId, String command) {
        this.serviceId = serviceId;
        this.command = command;
    }

    public UUID serviceId() {
        return serviceId;
    }

    public String command() {
        return command;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeUniqueId(serviceId).writeString(command);
    }

    @Override
    public void read(DataBuf buf) {
        this.serviceId = buf.readUniqueId();
        this.command = buf.readString();
    }
}
