package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

import java.util.List;
import java.util.UUID;

/**
 * Wrapper to node: the buffered console backlog, sent once on subscribe.
 *
 * <p>Attaching to a server that has been running for an hour should not show an
 * empty screen until the next log line happens to arrive, so the wrapper keeps
 * a bounded ring buffer per service and replays it on attach.
 */
public final class ConsoleHistoryPacket extends Packet {

    private UUID serviceId;
    private String serviceName;
    private List<String> lines;

    public ConsoleHistoryPacket() {
    }

    public ConsoleHistoryPacket(UUID serviceId, String serviceName, List<String> lines) {
        this.serviceId = serviceId;
        this.serviceName = serviceName;
        this.lines = lines;
    }

    public UUID serviceId() {
        return serviceId;
    }

    public String serviceName() {
        return serviceName;
    }

    public List<String> lines() {
        return lines == null ? List.of() : lines;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeUniqueId(serviceId)
                .writeString(serviceName)
                .writeCollection(lines(), DataBuf::writeString);
    }

    @Override
    public void read(DataBuf buf) {
        this.serviceId = buf.readUniqueId();
        this.serviceName = buf.readString();
        this.lines = buf.readList(DataBuf::readString);
    }
}
