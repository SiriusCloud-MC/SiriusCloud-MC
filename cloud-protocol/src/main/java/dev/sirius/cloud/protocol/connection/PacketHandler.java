package dev.sirius.cloud.protocol.connection;

import dev.sirius.cloud.protocol.packet.Packet;

/**
 * Per-connection callbacks. Implementations run on a Netty event loop thread,
 * so anything blocking (disk, process spawning, HTTP) must be handed off.
 */
public interface PacketHandler {

    default void onConnect(NetworkChannel channel) {
    }

    default void onDisconnect(NetworkChannel channel) {
    }

    void onPacket(NetworkChannel channel, Packet packet);

    default void onException(NetworkChannel channel, Throwable throwable) {
    }
}
