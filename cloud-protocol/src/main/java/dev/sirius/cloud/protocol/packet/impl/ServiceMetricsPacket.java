package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

import java.util.UUID;

/**
 * Service to node: how healthy it is, on each heartbeat.
 *
 * <p>Kept on the node rather than pushed to every other service. Nothing in a
 * server needs its neighbours' tick rate, and ten-second health reports from
 * every service to every service would be the noisiest traffic in the cloud.
 */
public final class ServiceMetricsPacket extends Packet {

    private UUID serviceId;
    private double tps;
    private double mspt;
    private int heapUsedMb;
    private int heapMaxMb;

    public ServiceMetricsPacket() {
    }

    public ServiceMetricsPacket(UUID serviceId, double tps, double mspt, int heapUsedMb, int heapMaxMb) {
        this.serviceId = serviceId;
        this.tps = tps;
        this.mspt = mspt;
        this.heapUsedMb = heapUsedMb;
        this.heapMaxMb = heapMaxMb;
    }

    public UUID serviceId() {
        return serviceId;
    }

    public double tps() {
        return tps;
    }

    public double mspt() {
        return mspt;
    }

    public int heapUsedMb() {
        return heapUsedMb;
    }

    public int heapMaxMb() {
        return heapMaxMb;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeUniqueId(serviceId)
                .writeLong(Double.doubleToLongBits(tps))
                .writeLong(Double.doubleToLongBits(mspt))
                .writeInt(heapUsedMb)
                .writeInt(heapMaxMb);
    }

    @Override
    public void read(DataBuf buf) {
        this.serviceId = buf.readUniqueId();
        this.tps = Double.longBitsToDouble(buf.readLong());
        this.mspt = Double.longBitsToDouble(buf.readLong());
        this.heapUsedMb = buf.readInt();
        this.heapMaxMb = buf.readInt();
    }
}
