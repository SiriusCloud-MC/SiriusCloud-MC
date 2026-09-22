package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

/** Wrapper to node: still alive, and here is the current memory commitment. */
public final class HeartbeatPacket extends Packet {

    private long timestamp;
    private int usedMemory;
    private int runningServices;

    public HeartbeatPacket() {
    }

    public HeartbeatPacket(long timestamp, int usedMemory, int runningServices) {
        this.timestamp = timestamp;
        this.usedMemory = usedMemory;
        this.runningServices = runningServices;
    }

    public long timestamp() {
        return timestamp;
    }

    public int usedMemory() {
        return usedMemory;
    }

    public int runningServices() {
        return runningServices;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeLong(timestamp).writeInt(usedMemory).writeInt(runningServices);
    }

    @Override
    public void read(DataBuf buf) {
        this.timestamp = buf.readLong();
        this.usedMemory = buf.readInt();
        this.runningServices = buf.readInt();
    }
}
