package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.api.group.ServiceGroup;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.protocol.packet.Packet;

/**
 * Node to wrapper: prepare a directory and spawn this service.
 *
 * <p>The group travels with the packet so the wrapper never needs its own copy
 * of the group configuration — the node stays the single source of truth.
 */
public final class ServiceStartPacket extends Packet {

    private ServiceInfo service;
    private ServiceGroup group;

    /** One-time token the spawned process uses to authenticate back to the node. */
    private String token;

    private String nodeHost;
    private int nodePort;

    public ServiceStartPacket() {
    }

    public ServiceStartPacket(ServiceInfo service, ServiceGroup group, String token, String nodeHost, int nodePort) {
        this.service = service;
        this.group = group;
        this.token = token;
        this.nodeHost = nodeHost;
        this.nodePort = nodePort;
    }

    public ServiceInfo service() {
        return service;
    }

    public ServiceGroup group() {
        return group;
    }

    public String token() {
        return token;
    }

    public String nodeHost() {
        return nodeHost;
    }

    public int nodePort() {
        return nodePort;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeObject(service)
                .writeObject(group)
                .writeString(token)
                .writeString(nodeHost)
                .writeInt(nodePort);
    }

    @Override
    public void read(DataBuf buf) {
        this.service = buf.readObject(ServiceInfo.class);
        this.group = buf.readObject(ServiceGroup.class);
        this.token = buf.readString();
        this.nodeHost = buf.readString();
        this.nodePort = buf.readInt();
    }
}
