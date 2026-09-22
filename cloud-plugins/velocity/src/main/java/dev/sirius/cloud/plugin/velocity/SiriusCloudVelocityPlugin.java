package dev.sirius.cloud.plugin.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import dev.sirius.cloud.api.driver.CloudDriver;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.platform.Platform;
import dev.sirius.cloud.api.service.ServiceInfo;
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
import dev.sirius.cloud.protocol.packet.impl.ServiceAvailabilityPacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceReadyPacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceStateUpdatePacket;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The proxy half of the cloud.
 *
 * <p>Backend servers are registered and unregistered as the node reports them,
 * so {@code velocity.toml} lists none and never needs editing. That is the
 * whole point of a cloud proxy: servers come and go constantly, and a static
 * list would be wrong within seconds of being written.
 */
@Plugin(
        id = "siriuscloud",
        name = "SiriusCloud",
        version = "1.0.0",
        description = "Connects this proxy to its SiriusCloud node.",
        authors = {"Sirius"}
)
public final class SiriusCloudVelocityPlugin {

    private static final int HEARTBEAT_SECONDS = 10;

    private final ProxyServer proxy;
    private final Logger logger;

    private ConnectionFile connection;
    private NetworkClient client;

    /** Backends the node has told us about, by service id. */
    private final Map<UUID, ServerInfo> registered = new ConcurrentHashMap<>();

    /** Which of those may receive players on join or via {@code /hub}. */
    private final Map<UUID, Boolean> fallbacks = new ConcurrentHashMap<>();

    private final AtomicBoolean authenticated = new AtomicBoolean();
    private final AtomicBoolean readySent = new AtomicBoolean();

    @Inject
    public SiriusCloudVelocityPlugin(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        CloudLogger.sink(logger::info);

        Path connectionFile = Path.of("cloud-connection.json");
        if (Files.notExists(connectionFile)) {
            logger.info("No cloud-connection.json found - running standalone, cloud features are off.");
            return;
        }

        try {
            connection = ConnectionFile.read(connectionFile);
        } catch (IOException exception) {
            logger.error("Could not read cloud-connection.json: {}", exception.getMessage());
            return;
        }

        proxy.getCommandManager().register(
                proxy.getCommandManager().metaBuilder("hub").aliases("lobby", "l").build(),
                new HubCommand());

        client = new NetworkClient(PacketRegistry.standard());
        client.connect(connection.nodeHost(), connection.nodePort(), new ProxyPacketHandler());

        CloudDriver.bind(new RemoteCloudDriver("SERVICE", client));

        proxy.getScheduler().buildTask(this, this::heartbeat)
                .repeat(HEARTBEAT_SECONDS, TimeUnit.SECONDS)
                .schedule();

        logger.info("Connecting to node at {}:{} as {}",
                connection.nodeHost(), connection.nodePort(), connection.serviceName());
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (client == null) {
            return;
        }
        client.send(new ServiceStateUpdatePacket(connection.serviceId(), ServiceState.STOPPING, -1));
        client.close();
        CloudDriver.unbind();
    }

    // -------------------------------------------------------------- routing

    /**
     * Sends joining players to a lobby.
     *
     * <p>Velocity's own {@code try} list is left empty, so without this a
     * player connecting would be kicked for having nowhere to go.
     */
    @Subscribe
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        pickFallback().ifPresent(event::setInitialServer);
    }

    /**
     * The least-loaded reachable fallback server.
     *
     * <p>Least-loaded rather than random so that a newly started lobby actually
     * takes some of the load instead of being ignored until chance finds it.
     */
    private Optional<RegisteredServer> pickFallback() {
        return registered.entrySet().stream()
                .filter(entry -> Boolean.TRUE.equals(fallbacks.get(entry.getKey())))
                .map(entry -> proxy.getServer(entry.getValue().getName()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .min(Comparator.comparingInt(server -> server.getPlayersConnected().size()));
    }

    private final class HubCommand implements SimpleCommand {

        @Override
        public void execute(Invocation invocation) {
            CommandSource source = invocation.source();
            if (!(source instanceof Player player)) {
                source.sendMessage(Component.text("Only players can use /hub.", NamedTextColor.RED));
                return;
            }

            Optional<RegisteredServer> target = pickFallback();
            if (target.isEmpty()) {
                player.sendMessage(Component.text("No lobby is available right now.", NamedTextColor.RED));
                return;
            }

            RegisteredServer server = target.get();
            if (player.getCurrentServer()
                    .map(current -> current.getServerInfo().getName().equals(server.getServerInfo().getName()))
                    .orElse(false)) {
                player.sendMessage(Component.text("You are already in a lobby.", NamedTextColor.YELLOW));
                return;
            }

            player.sendMessage(Component.text("Sending you to " + server.getServerInfo().getName() + "...",
                    NamedTextColor.GRAY));
            player.createConnectionRequest(server).fireAndForget();
        }
    }

    // ------------------------------------------------------------- registry

    private void setAvailable(ServiceInfo service, boolean available) {
        if (available) {
            ServerInfo info = new ServerInfo(
                    service.name(), new InetSocketAddress(service.host(), service.port()));

            // Re-registering the same name throws; drop any stale entry first,
            // which also covers a server that restarted onto a new port.
            proxy.getServer(info.getName())
                    .ifPresent(existing -> proxy.unregisterServer(existing.getServerInfo()));

            proxy.registerServer(info);
            registered.put(service.uniqueId(), info);
            fallbacks.put(service.uniqueId(), service.fallback());

            logger.info("Registered {} at {}:{}{}",
                    service.name(), service.host(), service.port(),
                    service.fallback() ? " (lobby)" : "");
            return;
        }

        ServerInfo info = registered.remove(service.uniqueId());
        fallbacks.remove(service.uniqueId());
        if (info == null) {
            return;
        }

        proxy.unregisterServer(info);
        logger.info("Unregistered {}", service.name());

        // Players still on it would otherwise sit on a dead connection until
        // they time out.
        List<Player> stranded = proxy.getAllPlayers().stream()
                .filter(player -> player.getCurrentServer()
                        .map(current -> current.getServerInfo().getName().equals(info.getName()))
                        .orElse(false))
                .toList();

        if (!stranded.isEmpty()) {
            Optional<RegisteredServer> lobby = pickFallback();
            stranded.forEach(player -> {
                if (lobby.isPresent()) {
                    player.sendMessage(Component.text(
                            info.getName() + " went away, moving you to a lobby.", NamedTextColor.YELLOW));
                    player.createConnectionRequest(lobby.get()).fireAndForget();
                } else {
                    player.disconnect(Component.text(
                            info.getName() + " went away and no lobby is available.", NamedTextColor.RED));
                }
            });
        }
    }

    private void heartbeat() {
        if (client != null && client.isConnected()) {
            client.send(new HeartbeatPacket(
                    System.currentTimeMillis(), 0, proxy.getPlayerCount()));
        }
    }

    private void sendReadyIfPossible() {
        if (!authenticated.get() || !readySent.compareAndSet(false, true)) {
            return;
        }
        client.send(new ServiceReadyPacket(
                connection.serviceId(),
                proxy.getConfiguration().getShowMaxPlayers(),
                "Velocity"));
        logger.info("Reported ready to the node.");
    }

    private final class ProxyPacketHandler implements PacketHandler {

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
                    logger.warn("The node rejected this proxy: {}", response.message());
                    channel.close();
                }
                return;
            }

            if (packet instanceof ServiceAvailabilityPacket availability) {
                setAvailable(availability.service(), availability.available());
            }
        }

        @Override
        public void onDisconnect(NetworkChannel channel) {
            authenticated.set(false);
            // The node may have restarted; it will re-send the full server list
            // on the next handshake, so readiness must be announced again.
            readySent.set(false);
        }
    }
}
