package dev.sirius.cloud.node.network;

import dev.sirius.cloud.api.event.EventManager;
import dev.sirius.cloud.api.event.events.WrapperConnectedEvent;
import dev.sirius.cloud.api.event.events.WrapperDisconnectedEvent;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.node.WrapperInfo;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.api.service.ServiceState;
import dev.sirius.cloud.node.config.NodeConfig;
import dev.sirius.cloud.node.group.GroupRegistry;
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
import dev.sirius.cloud.protocol.packet.impl.ConsoleCommandPacket;
import dev.sirius.cloud.protocol.packet.impl.ConsoleHistoryPacket;
import dev.sirius.cloud.protocol.packet.impl.ConsoleLinePacket;
import dev.sirius.cloud.protocol.packet.impl.GroupListRequestPacket;
import dev.sirius.cloud.protocol.packet.impl.GroupListResponsePacket;
import dev.sirius.cloud.protocol.packet.impl.HandshakePacket;
import dev.sirius.cloud.protocol.packet.impl.HandshakeResponsePacket;
import dev.sirius.cloud.protocol.packet.impl.HeartbeatPacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceCrashReportPacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceListRequestPacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceListResponsePacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceReadyPacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceStartRequestPacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceStartResponsePacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceStateUpdatePacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceStopPacket;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

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

    public NodePacketHandler(NodeConfig config,
                             ServiceManager serviceManager,
                             ServiceRegistry services,
                             GroupRegistry groups,
                             WrapperRegistry wrappers,
                             EventManager events,
                             NodeConsole console) {
        this.config = config;
        this.serviceManager = serviceManager;
        this.services = services;
        this.groups = groups;
        this.wrappers = wrappers;
        this.events = events;
        this.console = console;
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

    private void handleHandshake(NetworkChannel channel, HandshakePacket handshake) {
        if (channel.authenticated()) {
            channel.close();
            return;
        }

        boolean accepted;
        String message;

        switch (handshake.type()) {
            case WRAPPER, API -> {
                accepted = config.secret().equals(handshake.credential());
                message = accepted ? "welcome" : "invalid secret";
            }
            case SERVICE -> {
                accepted = handshake.serviceId() != null
                        && serviceManager.consumeToken(handshake.serviceId(), handshake.credential());
                message = accepted ? "welcome" : "invalid or already-used service token";
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
        } else {
            LOGGER.debug("{} '{}' connected", handshake.type(), handshake.name());
        }
    }

    @Override
    public void onDisconnect(NetworkChannel channel) {
        if (channel.type() == ConnectionType.WRAPPER && channel.name() != null) {
            wrappers.unregister(channel.name()).ifPresent(wrapper -> {
                events.post(new WrapperDisconnectedEvent(wrapper.info()));
                LOGGER.warn("Wrapper '{}' disconnected", wrapper.name());

                // The wrapper owned those processes. With it gone we cannot know
                // or control their fate, so they stop being schedulable.
                List<ServiceInfo> orphaned = services.markWrapperServicesCrashed(wrapper.name());
                orphaned.forEach(service -> services.remove(service.uniqueId()));
                if (!orphaned.isEmpty()) {
                    LOGGER.warn("Released {} service(s) that were running on it", orphaned.size());
                }
            });
        } else if (channel.type() == ConnectionType.SERVICE && channel.serviceId() != null) {
            // Losing the in-service plugin is not proof the process died; the
            // wrapper reports that. Note it and let the wrapper be authoritative.
            services.byId(channel.serviceId()).ifPresent(service ->
                    LOGGER.debug("Plugin connection for {} closed", service.name()));
        }
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
