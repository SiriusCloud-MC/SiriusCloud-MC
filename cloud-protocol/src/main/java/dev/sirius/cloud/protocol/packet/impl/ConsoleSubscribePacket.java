package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

import java.util.UUID;

/**
 * Node to wrapper: start or stop streaming a service's console.
 *
 * <p>Console output is not sent unless somebody is watching. A cloud running
 * thirty servers produces a continuous stream of log lines, and shipping all of
 * it across the network so the node can discard it would be pure waste — so the
 * wrapper buffers locally and only streams on request.
 */
public final class ConsoleSubscribePacket extends Packet {

    private UUID serviceId;
    private boolean subscribe;

    public ConsoleSubscribePacket() {
    }

    public ConsoleSubscribePacket(UUID serviceId, boolean subscribe) {
        this.serviceId = serviceId;
        this.subscribe = subscribe;
    }

    public UUID serviceId() {
        return serviceId;
    }

    public boolean subscribe() {
        return subscribe;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeUniqueId(serviceId).writeBoolean(subscribe);
    }

    @Override
    public void read(DataBuf buf) {
        this.serviceId = buf.readUniqueId();
        this.subscribe = buf.readBoolean();
    }
}
