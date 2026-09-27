package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.protocol.packet.Packet;

/**
 * Node to every service: this is the current state of that service.
 *
 * <p>Pushed on every lifecycle change, property change and occupancy change,
 * so a plugin's view of the cloud is live rather than whatever it last
 * queried - and its local event bus fires the same lifecycle events the
 * node's does. Without this a plugin can only find out a server went
 * {@code INGAME} by asking, over and over.
 */
public final class ServiceUpdatePacket extends Packet {

    private ServiceInfo service;
    private boolean removed;

    public ServiceUpdatePacket() {
    }

    public ServiceUpdatePacket(ServiceInfo service, boolean removed) {
        this.service = service;
        this.removed = removed;
    }

    public ServiceInfo service() {
        return service;
    }

    public boolean removed() {
        return removed;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeObject(service).writeBoolean(removed);
    }

    @Override
    public void read(DataBuf buf) {
        this.service = buf.readObject(ServiceInfo.class);
        this.removed = buf.readBoolean();
    }
}
