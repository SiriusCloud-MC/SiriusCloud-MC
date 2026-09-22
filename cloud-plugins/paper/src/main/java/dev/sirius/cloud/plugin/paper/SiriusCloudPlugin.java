package dev.sirius.cloud.plugin.paper;

import dev.sirius.cloud.api.driver.CloudDriver;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.platform.Platform;
import dev.sirius.cloud.api.service.ServiceState;
import dev.sirius.cloud.driver.RemoteCloudDriver;
import dev.sirius.cloud.protocol.connection.NetworkChannel;
import dev.sirius.cloud.protocol.connection.NetworkClient;
import dev.sirius.cloud.protocol.connection.PacketHandler;
import dev.sirius.cloud.protocol.packet.ConnectionType;
import dev.sirius.cloud.protocol.packet.Packet;
import dev.sirius.cloud.protocol.packet.PacketRegistry;
import dev.sirius.cloud.protocol.packet.impl.HandshakePacket;
import dev.sirius.cloud.protocol.packet.impl.HandshakeResponsePacket;
import dev.sirius.cloud.protocol.packet.impl.HeartbeatPacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceReadyPacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceStateUpdatePacket;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The in-service half of the cloud.
 *
 * <p>Its one job in this milestone is to make {@code RUNNING} mean something:
 * the node marks a service running when this plugin reports readiness, not when
 * a log line looks right. Player tracking, transfers and messaging land here in
 * later milestones — the connection they need already exists.
 */
public final class SiriusCloudPlugin extends JavaPlugin implements Listener {

    private static final CloudLogger LOGGER = CloudLogger.of("SiriusCloud");

    private static final int HEARTBEAT_TICKS = 20 * 10;

    private ConnectionFile connection;
    private NetworkClient client;

    private final AtomicBoolean authenticated = new AtomicBoolean();
    private final AtomicBoolean serverLoaded = new AtomicBoolean();
    private final AtomicBoolean readySent = new AtomicBoolean();

    @Override
    public void onEnable() {
        CloudLogger.sink(line -> getLogger().info(line));

        Path connectionFile = getServer().getWorldContainer().toPath().resolve("cloud-connection.json");
        if (Files.notExists(connectionFile)) {
            getLogger().info("No cloud-connection.json found - running standalone, cloud features are off.");
            return;
        }

        try {
            connection = ConnectionFile.read(connectionFile);
        } catch (IOException exception) {
            getLogger().severe("Could not read cloud-connection.json: " + exception.getMessage());
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);

        client = new NetworkClient(PacketRegistry.standard());
        client.connect(connection.nodeHost(), connection.nodePort(), new ServicePacketHandler());

        CloudDriver.bind(new RemoteCloudDriver("SERVICE", client));

        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::heartbeat,
                HEARTBEAT_TICKS, HEARTBEAT_TICKS);

        getLogger().info("Connecting to node at " + connection.nodeHost() + ":" + connection.nodePort()
                + " as " + connection.serviceName());
    }

    @Override
    public void onDisable() {
        if (client == null) {
            return;
        }

        // Tell the node we are going down on purpose, so the wrapper's exit
        // report is confirmation rather than the first anyone hears of it.
        client.send(new ServiceStateUpdatePacket(connection.serviceId(), ServiceState.STOPPING, -1));
        client.close();

        CloudDriver.unbind();
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        serverLoaded.set(true);
        sendReadyIfPossible();
    }

    /**
     * Readiness needs both the handshake and the server load, and they can
     * complete in either order — the node is reachable long before the world
     * finishes generating, but on a slow network it may not be.
     */
    private void sendReadyIfPossible() {
        if (!authenticated.get() || !serverLoaded.get()) {
            return;
        }
        if (!readySent.compareAndSet(false, true)) {
            return;
        }

        client.send(new ServiceReadyPacket(
                connection.serviceId(),
                Bukkit.getMaxPlayers(),
                Bukkit.getVersion()));

        getLogger().info("Reported ready to the node.");
    }

    private void heartbeat() {
        if (client != null && client.isConnected()) {
            client.send(new HeartbeatPacket(System.currentTimeMillis(), 0, Bukkit.getOnlinePlayers().size()));
        }
    }

    /** Handles the service side of the protocol. */
    private final class ServicePacketHandler implements PacketHandler {

        @Override
        public void onConnect(NetworkChannel channel) {
            channel.send(new HandshakePacket(
                    ConnectionType.SERVICE,
                    connection.serviceName(),
                    connection.token(),
                    connection.serviceId(),
                    Platform.describe()));
        }

        @Override
        public void onPacket(NetworkChannel channel, Packet packet) {
            if (packet instanceof HandshakeResponsePacket response) {
                if (response.accepted()) {
                    channel.authenticated(true);
                    authenticated.set(true);
                    sendReadyIfPossible();
                } else {
                    getLogger().warning("The node rejected this service: " + response.message());
                    channel.close();
                }
            }
        }

        @Override
        public void onDisconnect(NetworkChannel channel) {
            authenticated.set(false);
            // A reconnect must re-announce readiness; the node may have
            // restarted and lost everything it knew about us.
            readySent.set(false);
        }
    }
}
