package dev.sirius.cloud.node.wrapper;

import dev.sirius.cloud.api.node.WrapperInfo;
import dev.sirius.cloud.protocol.connection.NetworkChannel;
import dev.sirius.cloud.protocol.packet.Packet;

/** A wrapper that has completed its handshake and can be given work. */
public final class ConnectedWrapper {

    private final WrapperInfo info;
    private final NetworkChannel channel;

    private volatile long lastHeartbeat = System.currentTimeMillis();

    public ConnectedWrapper(WrapperInfo info, NetworkChannel channel) {
        this.info = info;
        this.channel = channel;
    }

    public WrapperInfo info() {
        return info;
    }

    public String name() {
        return info.name();
    }

    public NetworkChannel channel() {
        return channel;
    }

    public void send(Packet packet) {
        channel.send(packet);
    }

    public long lastHeartbeat() {
        return lastHeartbeat;
    }

    public void heartbeat(int usedMemory) {
        this.lastHeartbeat = System.currentTimeMillis();
        this.info.usedMemory(usedMemory);
    }

    public boolean isAlive() {
        return channel.isOpen();
    }
}
