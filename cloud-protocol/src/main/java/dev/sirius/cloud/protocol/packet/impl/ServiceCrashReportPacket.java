package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

import java.util.List;
import java.util.UUID;

/**
 * Wrapper to node: a service died without being asked to, and here is what it
 * said on the way out.
 *
 * <p>This exists because service consoles are hidden behind {@code attach}, and
 * a service that fails during startup is dead before anyone could attach to it.
 * Without this the operator sees "exited with code 1" and nothing else, while
 * the actual cause — a missing JVM, a port clash, a broken plugin — sits in a
 * buffer nobody will ever read. The tail of that buffer is pushed unasked,
 * because a crash is exactly the moment the output stops being noise.
 */
public final class ServiceCrashReportPacket extends Packet {

    private UUID serviceId;
    private String serviceName;
    private int exitCode;
    private List<String> lastLines;

    public ServiceCrashReportPacket() {
    }

    public ServiceCrashReportPacket(UUID serviceId, String serviceName, int exitCode, List<String> lastLines) {
        this.serviceId = serviceId;
        this.serviceName = serviceName;
        this.exitCode = exitCode;
        this.lastLines = lastLines;
    }

    public UUID serviceId() {
        return serviceId;
    }

    public String serviceName() {
        return serviceName;
    }

    public int exitCode() {
        return exitCode;
    }

    public List<String> lastLines() {
        return lastLines == null ? List.of() : lastLines;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeUniqueId(serviceId)
                .writeString(serviceName)
                .writeInt(exitCode)
                .writeCollection(lastLines(), DataBuf::writeString);
    }

    @Override
    public void read(DataBuf buf) {
        this.serviceId = buf.readUniqueId();
        this.serviceName = buf.readString();
        this.exitCode = buf.readInt();
        this.lastLines = buf.readList(DataBuf::readString);
    }
}
