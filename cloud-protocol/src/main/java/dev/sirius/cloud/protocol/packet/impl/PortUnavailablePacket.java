package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

/**
 * Wrapper to node: something outside the cloud holds this port on my machine.
 *
 * <p>The node allocates ports from its own bookkeeping, which knows nothing
 * of a stray process or another program on the machine. Without this the next
 * start of the group is handed the same lowest-free port and fails the same
 * way, every time, until the backoff gives up trying.
 */
public final class PortUnavailablePacket extends Packet {

    private int port;

    public PortUnavailablePacket() {
    }

    public PortUnavailablePacket(int port) {
        this.port = port;
    }

    public int port() {
        return port;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeInt(port);
    }

    @Override
    public void read(DataBuf buf) {
        this.port = buf.readInt();
    }
}
