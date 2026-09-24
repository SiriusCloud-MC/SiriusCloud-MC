package dev.sirius.cloud.node.network;

import dev.sirius.cloud.api.event.EventManager;
import dev.sirius.cloud.api.event.events.ServiceCreatedEvent;
import dev.sirius.cloud.api.event.events.WrapperConnectedEvent;
import dev.sirius.cloud.api.event.events.WrapperDisconnectedEvent;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.messaging.ChannelMessage;
import dev.sirius.cloud.api.node.WrapperInfo;
import dev.sirius.cloud.api.player.CloudPlayer;
import dev.sirius.cloud.api.event.events.PlayerConnectEvent;
import dev.sirius.cloud.api.event.events.PlayerDisconnectEvent;
import dev.sirius.cloud.api.event.events.PlayerSwitchServerEvent;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.api.service.ServiceState;
import dev.sirius.cloud.api.service.ServiceType;
import dev.sirius.cloud.node.LocalCloudDriver;
import dev.sirius.cloud.node.config.NodeConfig;
import dev.sirius.cloud.node.group.GroupRegistry;
import dev.sirius.cloud.node.player.PlayerManager;
import dev.sirius.cloud.node.player.PlayerRegistry;
import dev.sirius.cloud.node.service.ServiceChannelRegistry;
import dev.sirius.cloud.node.service.ServiceManager;
import dev.sirius.cloud.node.service.ServiceRegistry;
import dev.sirius.cloud.node.wrapper.ConnectedWrapper;
import dev.sirius.cloud.node.console.NodeConsole;
import dev.sirius.cloud.node.wrapper.WrapperRegistry;
import dev.sirius.cloud.protocol.connection.NetworkChannel;
import dev.sirius.cloud.protocol.connection.PacketHandler;
import dev.sirius.cloud.protocol.packet.ConnectionType;
import dev.sirius.cloud.protocol.packet.Packet;
import dev.sirius.cloud.protocol.packet.impl.AcknowledgePacket;
import dev.sirius.cloud.protocol.packet.impl.ChannelMessagePacket;
import dev.sirius.cloud.protocol.packet.impl.ConsoleCommandPacket;
import dev.sirius.cloud.protocol.packet.impl.ConsoleHistoryPacket;
import dev.sirius.cloud.protocol.packet.impl.ConsoleLinePacket;
import dev.sirius.cloud.protocol.packet.impl.GroupListRequestPacket;
import dev.sirius.cloud.protocol.packet.impl.GroupListResponsePacket;
import dev.sirius.cloud.protocol.packet.impl.HandshakePacket;
import dev.sirius.cloud.protocol.packet.impl.HandshakeResponsePacket;
import dev.sirius.cloud.protocol.packet.impl.HeartbeatPacket;
import dev.sirius.cloud.protocol.packet.impl.NodeInfoRequestPacket;
import dev.sirius.cloud.protocol.packet.impl.NodeInfoResponsePacket;
import dev.sirius.cloud.protocol.packet.impl.PlayerConnectRequestPacket;
import dev.sirius.cloud.protocol.packet.impl.PlayerDisconnectPacket;
import dev.sirius.cloud.protocol.packet.impl.PlayerKickPacket;
import dev.sirius.cloud.protocol.packet.impl.PlayerListRequestPacket;
import dev.sirius.cloud.protocol.packet.impl.PlayerListResponsePacket;
import dev.sirius.cloud.protocol.packet.impl.PlayerLoginPacket;
import dev.sirius.cloud.protocol.packet.impl.PlayerMessagePacket;
import dev.sirius.cloud.protocol.packet.impl.PlayerSnapshotPacket;
import dev.sirius.cloud.protocol.packet.impl.PlayerSwitchServerPacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceAvailabilityPacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceCrashReportPacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceListRequestPacket;
import dev.sirius.cloud.protocol.packet.impl.ServicePlayerUpdatePacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceListResponsePacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceReadyPacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceSnapshotPacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceStartRequestPacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceStartResponsePacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceStateUpdatePacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceStopPacket;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Every inbound packet the node knows how to answer. */
public final class NodePacketHandler implements PacketHandler {

    private static final CloudLogger LOGGER = CloudLogger.of("Network");

    private final NodeConfig config;
    private final ServiceManager serviceManager;
    private final ServiceRegistry services;
    private final GroupRegistry groups;
    private final WrapperRegistry wrappers;
    private final EventManager events;
    private final NodeConsole console;
    private final ServiceChannelRegistry serviceChannels;
    private final PlayerRegistry players;
    private final PlayerManager playerManager;
    private final LocalCloudDriver driver;

    public NodePacketHandler(NodeConfig config,
                             ServiceManager serviceManager,
                             ServiceRegistry services,
                             GroupRegistry groups,
                             WrapperRegistry wrappers,
                             EventManager events,
                             NodeConsole console,
                             ServiceChannelRegistry serviceChannels,
                             PlayerRegistry players,
                             PlayerManager playerManager,
                             LocalCloudDriver driver) {
        this.config = config;
        this.serviceManager = serviceManager;
        this.services = services;
        this.groups = groups;
        this.wrappers = wrappers;
        this.events = events;
        this.console = console;
        this.serviceChannels = serviceChannels;
        this.players = players;
        this.playerManager = playerManager;
        this.driver = driver;
    }

    @Override
    public void onPacket(NetworkChannel channel, Packet packet) {
        if (packet instanceof HandshakePacket handshake) {
            handleHandshake(channel, handshake);
            return;
        }

        // Nothing but a handshake is processed on an unauthenticated channel.
        if (!channel.authenticated()) {
            LOGGER.warn("Dropping {} from unauthenticated {}",
                    packet.getClass().getSimpleName(), channel.remoteAddress());
            channel.close();
            return;
        }

        // An API client may be restricted to observation without revoking its
        // credentials. Checked centrally rather than per packet, so a new
        // mutating packet is covered by default instead of by remembering.
        if (channel.type() == ConnectionType.API && config.apiReadOnly() && mutates(packet)) {
            LOGGER.warn("Refusing {} from read-only API client {}",
                    packet.getClass().getSimpleName(), channel.name());
            if (packet.queryId() != null) {
                channel.respond(packet, AcknowledgePacket.fail("This API client is read-only"));
            }
            return;
        }

        if (packet instanceof HeartbeatPacket heartbeat) {
            wrappers.byChannel(channel).ifPresent(wrapper -> wrapper.heartbeat(heartbeat.usedMemory()));
            // Echoed so the peer's read timeout sees traffic from us too.
            channel.send(new HeartbeatPacket(System.currentTimeMillis(), 0, services.size()));

        } else if (packet instanceof ServiceStateUpdatePacket update) {
            serviceManager.updateState(update.serviceId(), update.state(), update.exitCode());

        } else if (packet instanceof ServiceReadyPacket ready) {
            services.byId(ready.serviceId()).ifPresent(service -> {
                service.maxPlayers(ready.maxPlayers());
                serviceManager.transition(service, ServiceState.RUNNING);
                LOGGER.info("{} is ready on port {} ({})", service.name(), service.port(), ready.version());
            });

        } else if (packet instanceof PlayerLoginPacket login) {
            players.add(login.player());
            events.post(new PlayerConnectEvent(login.player()));
            LOGGER.info("{} joined via {} ({} online)",
                    login.player().name(), login.player().proxyName(), players.count());

        } else if (packet instanceof PlayerDisconnectPacket disconnect) {
            players.remove(disconnect.playerId()).ifPresent(player -> {
                events.post(new PlayerDisconnectEvent(player));
                LOGGER.info("{} left ({} online)", player.name(), players.count());
            });

        } else if (packet instanceof PlayerSwitchServerPacket switched) {
            players.byId(switched.playerId()).ifPresent(player -> {
                String previous = player.serverName().orElse(null);
                player.server(switched.serverId(), switched.serverName());
                // They have landed, so they now show in that service's own
                // player count and must stop being counted as in flight.
                if (switched.serverId() != null) {
                    playerManager.transferCompleted(switched.serverId());
                }
                events.post(new PlayerSwitchServerEvent(player, previous));
                LOGGER.debug("{}: {} -> {}", player.name(), previous, switched.serverName());
            });

        } else if (packet instanceof PlayerSnapshotPacket snapshot) {
            List<CloudPlayer> dropped =
                    players.replaceProxyPlayers(snapshot.proxyId(), snapshot.players());
            if (!snapshot.players().isEmpty() || !dropped.isEmpty()) {
                LOGGER.info("Proxy re-synced: {} player(s) online, {} stale entr(ies) dropped",
                        snapshot.players().size(), dropped.size());
            }

        } else if (packet instanceof PlayerConnectRequestPacket request) {
            // The same packet the node sends a proxy, arriving in the other
            // direction: here it is a plugin asking the node to move somebody.
            // A name matching a service goes there exactly; anything else is a
            // group, and the node balances across it — so one packet covers
            // both connect() and connectToGroup().
            boolean isService = services.byName(request.serviceName()).isPresent();
            (isService
                    ? playerManager.connect(request.playerId(), request.serviceName())
                    : playerManager.connectToGroup(request.playerId(), request.serviceName()))
                    .whenComplete((ignored, error) -> acknowledge(channel, request, error));

        } else if (packet instanceof PlayerMessagePacket message) {
            (message.isBroadcast()
                    ? playerManager.broadcast(message.message())
                    : playerManager.sendMessage(message.playerId(), message.message()))
                    .whenComplete((ignored, error) -> acknowledge(channel, message, error));

        } else if (packet instanceof PlayerKickPacket kick) {
            playerManager.kick(kick.playerId(), kick.reason())
                    .whenComplete((ignored, error) -> acknowledge(channel, kick, error));

        } else if (packet instanceof PlayerListRequestPacket request) {
            List<CloudPlayer> result = request.serviceFilter() == null
                    ? new ArrayList<>(players.all())
                    : players.onService(request.serviceFilter());
            channel.respond(request, new PlayerListResponsePacket(result));

        } else if (packet instanceof ServicePlayerUpdatePacket update) {
            services.byId(update.serviceId()).ifPresent(service -> {
                service.playerCount(update.playerCount());

                boolean capacityChanged = service.maxPlayers() != update.maxPlayers();
                service.maxPlayers(update.maxPlayers());

                // A backend whose slot count moved changes what every proxy
                // should be advertising, so they are told again.
                if (capacityChanged
                        && service.type() == ServiceType.SERVER
                        && service.state() == ServiceState.RUNNING) {
                    serviceChannels.broadcastToProxies(services,
                            new ServiceAvailabilityPacket(service, true));
                }
            });

        } else if (packet instanceof ServiceSnapshotPacket snapshot) {
            if (channel.type() == ConnectionType.WRAPPER) {
                adoptWrapperServices(channel, snapshot);
            } else {
                LOGGER.warn("Ignoring a service snapshot from non-wrapper {}", channel);
            }

        } else if (packet instanceof ChannelMessagePacket message) {
            // The source is stamped here from the authenticated connection and
            // never taken from the sender, so a service cannot publish under
            // another's name.
            String source = channel.type() == ConnectionType.SERVICE && channel.name() != null
                    ? channel.name()
                    : "node";
            message.sourceService(source);

            serviceChannels.broadcastToServices(message, channel.serviceId());
            driver.deliverChannelMessage(new ChannelMessage(
                    message.channel(), message.payload(), source));

        } else if (packet instanceof ConsoleLinePacket line) {
            // Only reaches a console that asked for it; see AttachCommand.
            console.printServiceLine(line.serviceId(), line.line());

        } else if (packet instanceof ConsoleHistoryPacket history) {
            console.printServiceBacklog(history.serviceId(), history.lines());

        } else if (packet instanceof ServiceCrashReportPacket crash) {
            // Printed unconditionally, unlike ordinary console output: this is
            // the only place the reason for a failed start ever surfaces.
            LOGGER.error("{} exited unexpectedly with code {}. Last output:",
                    crash.serviceName(), crash.exitCode());
            if (crash.lastLines().isEmpty()) {
                LOGGER.error("  (the service produced no output)");
            } else {
                crash.lastLines().forEach(line -> LOGGER.error("  | {}", line));
            }

        } else if (packet instanceof ServiceListRequestPacket request) {
            List<ServiceInfo> result = request.groupFilter() == null
                    ? new ArrayList<>(services.all())
                    : services.ofGroup(request.groupFilter());
            channel.respond(request, new ServiceListResponsePacket(result));

        } else if (packet instanceof NodeInfoRequestPacket request) {
            channel.respond(request, new NodeInfoResponsePacket(
                    driver.describeNode(),
                    List.of(driver.describeNode()),
                    new ArrayList<>(driver.describeWrappers())));

        } else if (packet instanceof GroupListRequestPacket request) {
            channel.respond(request, new GroupListResponsePacket(new ArrayList<>(groups.all())));

        } else if (packet instanceof ServiceStartRequestPacket request) {
            serviceManager.start(request.groupName()).whenComplete((service, error) -> {
                if (error != null) {
                    channel.respond(request, new ServiceStartResponsePacket(false, rootMessage(error), null));
                } else {
                    channel.respond(request, new ServiceStartResponsePacket(true, "", service));
                }
            });

        } else if (packet instanceof ServiceStopPacket request) {
            serviceManager.stop(request.serviceId(), request.force()).whenComplete((ignored, error) -> {
                if (request.queryId() != null) {
                    channel.respond(request, error == null
                            ? AcknowledgePacket.ok()
                            : AcknowledgePacket.fail(rootMessage(error)));
                }
            });

        } else if (packet instanceof ConsoleCommandPacket command) {
            serviceManager.dispatchCommand(command.serviceId(), command.command());
            if (command.queryId() != null) {
                channel.respond(command, AcknowledgePacket.ok());
            }

        } else {
            LOGGER.debug("No handler for {} from {}", packet.getClass().getSimpleName(), channel);
        }
    }

    /** Whether a packet changes cloud state, as opposed to reading it. */
    private static boolean mutates(Packet packet) {
        return packet instanceof ServiceStartRequestPacket
                || packet instanceof ServiceStopPacket
                || packet instanceof ConsoleCommandPacket
                || packet instanceof PlayerConnectRequestPacket
                || packet instanceof PlayerMessagePacket
                || packet instanceof PlayerKickPacket
                || packet instanceof ChannelMessagePacket;
    }

    private void handleHandshake(NetworkChannel channel, HandshakePacket handshake) {
        if (channel.authenticated()) {
            channel.close();
            return;
        }

        boolean accepted;
        String message;

        switch (handshake.type()) {
            case WRAPPER -> {
                accepted = config.secret().equals(handshake.credential());
                message = accepted ? "welcome" : "invalid secret";
            }
            case API -> {
                // A different secret, so a leaked API token cannot be used to
                // register a machine and be handed processes to run.
                accepted = config.apiSecret().equals(handshake.credential());
                message = accepted ? "welcome" : "invalid API secret";
            }
            case SERVICE -> {
                accepted = handshake.serviceId() != null
                        && serviceManager.verifyToken(handshake.serviceId(), handshake.credential());
                message = accepted ? "welcome" : "invalid service token";

                // A service proving its identity is the real one, so it
                // supersedes any channel still registered under that id rather
                // than being refused. The alternative loses to a race the node
                // cannot win: a reconnecting service knows its old socket is
                // dead well before the node observes the close, and refusing it
                // then would lock out the very service that is trying to
                // recover.
                if (accepted) {
                    serviceChannels.byService(handshake.serviceId()).ifPresent(existing -> {
                        if (existing != channel) {
                            LOGGER.debug("{} reconnected; dropping its previous connection",
                                    handshake.name());
                            existing.close();
                        }
                    });
                }
            }
            default -> {
                accepted = false;
                message = "unknown connection type";
            }
        }

        channel.send(new HandshakeResponsePacket(accepted, message));

        if (!accepted) {
            LOGGER.warn("Rejected {} handshake from {}: {}",
                    handshake.type(), channel.remoteAddress(), message);
            channel.close();
            return;
        }

        channel.authenticated(true);
        channel.type(handshake.type());
        channel.name(handshake.name());
        channel.serviceId(handshake.serviceId());

        if (handshake.type() == ConnectionType.WRAPPER) {
            String host = remoteHost(channel);
            WrapperInfo info = new WrapperInfo(
                    handshake.name(), host, config.maxMemory(), handshake.platform());
            ConnectedWrapper wrapper = new ConnectedWrapper(info, channel);
            wrappers.register(wrapper);
            events.post(new WrapperConnectedEvent(info));
            LOGGER.info("Wrapper '{}' connected from {} [{}]", handshake.name(), host, handshake.platform());
        } else if (handshake.type() == ConnectionType.SERVICE && handshake.serviceId() != null) {
            serviceChannels.register(handshake.serviceId(), channel);

            // A proxy needs the servers that already exist, not only the ones
            // that appear after it. Without this, restarting a proxy leaves it
            // blind to every server that was running at the time.
            services.byId(handshake.serviceId())
                    .filter(service -> service.type() == ServiceType.PROXY)
                    .ifPresent(proxy -> seedProxy(channel, proxy.name()));

            LOGGER.debug("{} '{}' connected", handshake.type(), handshake.name());
        } else {
            LOGGER.debug("{} '{}' connected", handshake.type(), handshake.name());
        }
    }

    @Override
    public void onDisconnect(NetworkChannel channel) {
        if (channel.type() == ConnectionType.WRAPPER && channel.name() != null) {
            wrappers.unregister(channel.name(), channel).ifPresent(wrapper -> {
                events.post(new WrapperDisconnectedEvent(wrapper.info()));
                LOGGER.warn("Wrapper '{}' disconnected", wrapper.name());

                // Its services are deliberately left in the registry. Losing the
                // control connection does not stop a single process — the
                // wrapper keeps them running so a blip cannot disconnect players
                // — so forgetting them here would release names and ports that
                // are still very much in use, and the provisioning loop would
                // then start replacements onto the same ports. They are
                // reconciled against the wrapper's own snapshot when it returns;
                // anything that really died is dropped then.
                List<ServiceInfo> unmanaged = services.ofWrapper(wrapper.name());
                if (!unmanaged.isEmpty()) {
                    LOGGER.warn("{} service(s) on it keep running but cannot be controlled "
                            + "until it reconnects", unmanaged.size());
                }
            });
        } else if (channel.type() == ConnectionType.SERVICE && channel.serviceId() != null) {
            serviceChannels.unregister(channel.serviceId());

            // Players reached the cloud through that proxy; with it gone they
            // are gone too, and leaving them listed would have the node trying
            // to act on people who are not there.
            List<CloudPlayer> lost = players.removeProxyPlayers(channel.serviceId());
            lost.forEach(player -> events.post(new PlayerDisconnectEvent(player)));
            if (!lost.isEmpty()) {
                LOGGER.warn("Proxy {} went away, dropping {} player(s)",
                        channel.name(), lost.size());
            }
            // Losing the in-service plugin is not proof the process died; the
            // wrapper reports that. Note it and let the wrapper be authoritative.
            services.byId(channel.serviceId()).ifPresent(service ->
                    LOGGER.debug("Plugin connection for {} closed", service.name()));
        }
    }

    /**
     * Reconciles the node's view of a wrapper against what it says it is
     * actually running, and marks it schedulable.
     *
     * <p>The snapshot replaces what the node believed about this machine,
     * exactly as a proxy's player snapshot does, and both directions matter:
     *
     * <ul>
     *   <li>Services the node still holds but the wrapper no longer reports
     *       really did die while we could not see them, so their names, ports
     *       and memory are released.
     *   <li>Services the wrapper reports but the node has never heard of are
     *       adopted rather than duplicated — this is a node that restarted
     *       under a machine that kept running. Starting a second {@code Lobby-1}
     *       beside the live one is exactly the failure this prevents.
     * </ul>
     *
     * <p>For a service the node already knows, its own state wins. The wrapper
     * can never observe {@code RUNNING} — that comes from the in-service plugin
     * straight to the node — so believing the wrapper here would demote every
     * running server back to {@code STARTING}.
     */
    private void adoptWrapperServices(NetworkChannel channel, ServiceSnapshotPacket snapshot) {
        // The name it authenticated under, never the one the packet claims:
        // this drops service records, and a wrapper must not be able to
        // reconcile away another machine's services by naming it.
        String wrapperName = channel.name();
        if (!wrapperName.equals(snapshot.wrapperName())) {
            LOGGER.warn("Wrapper '{}' sent a snapshot naming '{}'; using the authenticated name",
                    wrapperName, snapshot.wrapperName());
        }

        Set<UUID> reported = new HashSet<>();
        snapshot.services().forEach(entry -> reported.add(entry.service().uniqueId()));

        List<ServiceInfo> vanished = services.ofWrapper(wrapperName).stream()
                .filter(service -> !reported.contains(service.uniqueId()))
                .toList();
        vanished.forEach(service -> serviceManager.forget(service.uniqueId()));

        List<ServiceInfo> adopted = new ArrayList<>();
        for (ServiceSnapshotPacket.Entry entry : snapshot.services()) {
            ServiceInfo service = entry.service();

            // Before the adoption check: a node that kept the record across a
            // wrapper blip still holds the token, but one that restarted does
            // not, and only the wrapper can supply it either way.
            serviceManager.restoreToken(service.uniqueId(), entry.token());

            if (services.byId(service.uniqueId()).isPresent()) {
                continue;
            }
            services.adopt(service);
            events.post(new ServiceCreatedEvent(service));
            adopted.add(service);
        }

        // Only the newly adopted ones are announced. Services the node kept
        // through the outage were never unregistered from any proxy, and
        // re-registering one drops and re-adds it underneath its players.
        adopted.stream()
                .filter(service -> service.type() == ServiceType.SERVER)
                .filter(service -> service.state() == ServiceState.RUNNING)
                .forEach(service -> serviceChannels.broadcastToProxies(
                        services, new ServiceAvailabilityPacket(service, true)));

        wrappers.byChannel(channel).ifPresent(wrapper -> wrapper.ready(true));

        if (!snapshot.services().isEmpty() || !vanished.isEmpty()) {
            LOGGER.info("Wrapper '{}' is running {} service(s): {} adopted, {} no longer there",
                    wrapperName, snapshot.services().size(), adopted.size(), vanished.size());
        }
    }

    /** Answers a request that carried a query id, with whatever actually happened. */
    private static void acknowledge(NetworkChannel channel, Packet request, Throwable error) {
        if (request.queryId() == null) {
            return;
        }
        channel.respond(request, error == null
                ? AcknowledgePacket.ok()
                : AcknowledgePacket.fail(rootMessage(error)));
    }

    /** Tells a freshly connected proxy about every server already running. */
    private void seedProxy(NetworkChannel channel, String proxyName) {
        List<ServiceInfo> reachable = ServiceChannelRegistry.reachableServers(services);
        reachable.forEach(server -> channel.send(new ServiceAvailabilityPacket(server, true)));
        LOGGER.info("Proxy {} connected; sent {} running server(s)", proxyName, reachable.size());
    }

    private static String remoteHost(NetworkChannel channel) {
        if (channel.remoteAddress() instanceof InetSocketAddress address) {
            return address.getAddress().getHostAddress();
        }
        return "unknown";
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
