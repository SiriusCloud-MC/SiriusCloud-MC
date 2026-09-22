package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.protocol.packet.Packet;

import java.util.List;

/** Reply to {@link ServiceListRequestPacket}. */
public final class ServiceListResponsePacket extends Packet {

    private List<ServiceInfo> services;

    public ServiceListResponsePacket() {
    }

    public ServiceListResponsePacket(List<ServiceInfo> services) {
        this.services = services;
    }

    public List<ServiceInfo> services() {
        return services == null ? List.of() : services;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeCollection(services(), DataBuf::writeObject);
    }

    @Override
    public void read(DataBuf buf) {
        this.services = buf.readList(b -> b.readObject(ServiceInfo.class));
    }
}
