package dev.sirius.cloud.wrapper.network;

import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.platform.Platform;
import dev.sirius.cloud.wrapper.config.WrapperConfig;
import dev.sirius.cloud.wrapper.process.ServiceProcessManager;
import dev.sirius.cloud.protocol.connection.NetworkChannel;
import dev.sirius.cloud.protocol.connection.PacketHandler;
import dev.sirius.cloud.protocol.packet.ConnectionType;
import dev.sirius.cloud.protocol.packet.Packet;
import dev.sirius.cloud.protocol.packet.impl.ConsoleCommandPacket;
import dev.sirius.cloud.protocol.packet.impl.ConsoleSubscribePacket;
import dev.sirius.cloud.protocol.packet.impl.HandshakePacket;
import dev.sirius.cloud.protocol.packet.impl.HandshakeResponsePacket;
import dev.sirius.cloud.protocol.packet.impl.HeartbeatPacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceStartPacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceStopPacket;

import java.util.function.Consumer;

/** The wrapper's side of the conversation: take orders, report back. */
public final class WrapperPacketHandler implements PacketHandler {

    private static final CloudLogger LOGGER = CloudLogger.of("Network");

    private final WrapperConfig config;
    private final ServiceProcessManager processes;
    private final Consumer<Boolean> connectionStateSink;
    private final Runnable onAuthenticationRejected;

    public WrapperPacketHandler(WrapperConfig config,
                                ServiceProcessManager processes,
                                Consumer<Boolean> connectionStateSink,
                                Runnable onAuthenticationRejected) {
        this.config = config;
        this.processes = processes;
        this.connectionStateSink = connectionStateSink;
        this.onAuthenticationRejected = onAuthenticationRejected;
    }

    @Override
    public void onConnect(NetworkChannel channel) {
        LOGGER.info("Connected to the node, authenticating as '{}'", config.name());
        channel.send(new HandshakePacket(
                ConnectionType.WRAPPER,
                config.name(),
                config.secret(),
                null,
                Platform.describe()));
    }

    @Override
    public void onPacket(NetworkChannel channel, Packet packet) {
        if (packet instanceof HandshakeResponsePacket response) {
            if (response.accepted()) {
                channel.authenticated(true);
                LOGGER.info("Authenticated with the node");
                connectionStateSink.accept(true);
            } else {
                LOGGER.error("The node rejected this wrapper: {}", response.message());
                LOGGER.error("Check that 'secret' in config.json matches the node's.");
                channel.close();
                // Reconnecting will be rejected identically every three seconds
                // forever: a wrong secret does not become right on its own.
                onAuthenticationRejected.run();
            }
            return;
        }

        if (!channel.authenticated()) {
            return;
        }

        if (packet instanceof ServiceStartPacket start) {
            processes.start(start.service(), start.group(), start.token(),
                    start.nodeHost(), start.nodePort(), start.forwardingSecret());

        } else if (packet instanceof ServiceStopPacket stop) {
            processes.stop(stop.serviceId(), stop.force());

        } else if (packet instanceof ConsoleCommandPacket command) {
            processes.sendCommand(command.serviceId(), command.command());

        } else if (packet instanceof ConsoleSubscribePacket subscribe) {
            processes.setConsoleSubscribed(subscribe.serviceId(), subscribe.subscribe());

        } else if (packet instanceof HeartbeatPacket) {
            // The node's echo. Receiving it is the point; nothing to do.
            LOGGER.debug("Heartbeat acknowledged");

        } else {
            LOGGER.debug("No handler for {}", packet.getClass().getSimpleName());
        }
    }

    @Override
    public void onDisconnect(NetworkChannel channel) {
        connectionStateSink.accept(false);
        // Services keep running: losing the control connection is not a reason
        // to disconnect players. The client reconnects on its own.
        LOGGER.warn("Lost the node connection, {} service(s) still running", processes.runningCount());
    }
}
