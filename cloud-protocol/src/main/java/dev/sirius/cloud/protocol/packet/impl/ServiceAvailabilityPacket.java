package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.protocol.packet.Packet;

/**
 * Node to proxy: a backend server became reachable, or stopped being.
 *
 * <p>This is what makes servers appear in a proxy without anyone editing
 * {@code velocity.toml}. The proxy registers and unregisters them as they come
 * and go, which is the whole point of running a cloud rather than a fixed set
 * of servers.
 *
 * <p>On connect the node sends one of these for every server already running,
 * so a proxy that starts late or restarts converges on the same state as one
 * that was there from the beginning.
 */
public final class ServiceAvailabilityPacket extends Packet {

    private ServiceInfo service;
    private boolean available;

    public ServiceAvailabilityPacket() {
    }

    public ServiceAvailabilityPacket(ServiceInfo service, boolean available) {
        this.service = service;
        this.available = available;
    }

    public ServiceInfo service() {
        return service;
    }

    public boolean available() {
        return available;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeObject(service).writeBoolean(available);
    }

    @Override
    public void read(DataBuf buf) {
        this.service = buf.readObject(ServiceInfo.class);
        this.available = buf.readBoolean();
    }
}
