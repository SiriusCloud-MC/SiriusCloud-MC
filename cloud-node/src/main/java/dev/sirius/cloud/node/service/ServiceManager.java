package dev.sirius.cloud.node.service;

import dev.sirius.cloud.api.event.EventManager;
import dev.sirius.cloud.api.event.events.ServiceCreatedEvent;
import dev.sirius.cloud.api.event.events.ServiceRemovedEvent;
import dev.sirius.cloud.api.event.events.ServiceStateChangedEvent;
import dev.sirius.cloud.api.group.ServiceGroup;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.service.ServiceId;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.api.service.ServiceState;
import dev.sirius.cloud.node.config.NodeConfig;
import dev.sirius.cloud.node.group.GroupRegistry;
import dev.sirius.cloud.node.wrapper.ConnectedWrapper;
import dev.sirius.cloud.node.wrapper.WrapperRegistry;
import dev.sirius.cloud.protocol.packet.impl.ConsoleCommandPacket;
import dev.sirius.cloud.protocol.packet.impl.ConsoleSubscribePacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceStartPacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceStopPacket;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns "start a Lobby" into a reserved name, port and machine, and a packet on
 * the wire. The node decides <em>what</em> runs <em>where</em>; the wrapper
 * only executes.
 */
public final class ServiceManager {

    private static final CloudLogger LOGGER = CloudLogger.of(ServiceManager.class);

    private final NodeConfig config;
    private final GroupRegistry groups;
    private final ServiceRegistry services;
    private final WrapperRegistry wrappers;
    private final EventManager events;

    /**
     * Each service's credential, valid for as long as the service is registered.
     *
     * <p>A token is bound to one service id, so it can only ever authenticate
     * as that service — which is the property that matters, and the one that
     * makes a readable {@code cloud-connection.json} harmless.
     *
     * <p>It is deliberately <em>not</em> consumed on first use. That was the
     * original design and it made any reconnection impossible: a service whose
     * connection dropped for a moment, or whose node restarted, presented the
     * same token from its working directory and was refused forever. What
     * single-use was really guarding against — a second connection claiming to
     * be a service that is already here — is handled where it belongs, by
     * superseding the existing channel at handshake.
     */
    private final Map<UUID, String> serviceTokens = new ConcurrentHashMap<>();

    public ServiceManager(NodeConfig config,
                          GroupRegistry groups,
                          ServiceRegistry services,
                          WrapperRegistry wrappers,
                          EventManager events) {
        this.config = config;
        this.groups = groups;
        this.services = services;
        this.wrappers = wrappers;
        this.events = events;
    }

    public CompletableFuture<ServiceInfo> start(String groupName) {
        Optional<ServiceGroup> group = groups.byName(groupName);
        if (group.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("No such group: " + groupName));
        }
        return start(group.get());
    }

    public CompletableFuture<ServiceInfo> start(ServiceGroup group) {
        if (services.activeCount(group.name()) >= group.maxServiceCount()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    group.name() + " is already at its maximum of " + group.maxServiceCount() + " services"));
        }

        if (services.committedMemory() + group.memory() > config.maxMemory()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Starting " + group.name() + " would commit "
                            + (services.committedMemory() + group.memory()) + "MB of "
                            + config.maxMemory() + "MB"));
        }

        Optional<ConnectedWrapper> target = wrappers.selectFor(group.memory());
        if (target.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    noWrapperMessage(group.memory())));
        }

        ConnectedWrapper wrapper = target.get();
        ServiceId serviceId = services.allocateId(group.name());
        int port = services.allocatePort(wrapper.name(), group.startPort());

        ServiceInfo service = new ServiceInfo(
                serviceId,
                group.type(),
                wrapper.name(),
                wrapper.info().host(),
                port,
                group.memory(),
                group.maxPlayers());

        service.fallback(group.fallback());

        services.add(service);
        events.post(new ServiceCreatedEvent(service));

        String token = UUID.randomUUID().toString();
        serviceTokens.put(service.uniqueId(), token);

        wrapper.send(new ServiceStartPacket(
                service, group, token, config.connectAddress(), config.port(),
                config.forwardingSecret()));

        LOGGER.info("Starting {} on {} (port {}, {}MB)",
                service.name(), wrapper.name(), port, group.memory());

        return CompletableFuture.completedFuture(service);
    }

    /** Why nothing could be scheduled, in terms the operator can act on. */
    private String noWrapperMessage(int requiredMemory) {
        if (wrappers.isEmpty()) {
            return "No wrapper is connected";
        }
        if (!wrappers.hasReady()) {
            return "No wrapper has finished registering its running services yet";
        }
        return "No connected wrapper has " + requiredMemory + "MB free";
    }

    public CompletableFuture<Void> stop(UUID uniqueId, boolean force) {
        Optional<ServiceInfo> service = services.byId(uniqueId);
        if (service.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Unknown service " + uniqueId));
        }

        ServiceInfo info = service.get();
        Optional<ConnectedWrapper> wrapper = wrappers.byName(info.wrapperName());
        if (wrapper.isEmpty()) {
            // The wrapper is gone, so nothing can stop the process for us.
            // Drop the record rather than leaving a ghost in the registry.
            LOGGER.warn("Wrapper {} for {} is gone, removing the service record",
                    info.wrapperName(), info.name());
            forget(uniqueId);
            return CompletableFuture.completedFuture(null);
        }

        transition(info, ServiceState.STOPPING);
        wrapper.get().send(new ServiceStopPacket(uniqueId, force));
        LOGGER.info("Stopping {}{}", info.name(), force ? " (forced)" : "");

        return CompletableFuture.completedFuture(null);
    }

    /**
     * Turns console streaming for a service on or off.
     *
     * <p>Nothing is streamed until something asks. The wrapper buffers locally,
     * so the cost of not watching is zero rather than "the node throws it away".
     */
    public void subscribeConsole(UUID uniqueId, boolean subscribe) {
        services.byId(uniqueId).ifPresent(service ->
                wrappers.byName(service.wrapperName()).ifPresent(wrapper ->
                        wrapper.send(new ConsoleSubscribePacket(uniqueId, subscribe))));
    }

    public void dispatchCommand(UUID uniqueId, String command) {
        services.byId(uniqueId).ifPresent(service ->
                wrappers.byName(service.wrapperName()).ifPresent(wrapper ->
                        wrapper.send(new ConsoleCommandPacket(uniqueId, command))));
    }

    /** Whether this is the credential issued to that service. */
    public boolean verifyToken(UUID serviceId, String token) {
        String expected = serviceTokens.get(serviceId);
        return expected != null && !expected.isBlank() && expected.equals(token);
    }

    /**
     * Re-arms the token of a service adopted from a wrapper's snapshot.
     *
     * <p>A restarted node has forgotten every token it ever issued, while the
     * processes it just adopted are still holding theirs. Without this they
     * would be visible in the registry and permanently unable to authenticate,
     * which is adoption doing half a job.
     */
    public void restoreToken(UUID serviceId, String token) {
        if (token != null && !token.isBlank()) {
            serviceTokens.putIfAbsent(serviceId, token);
        }
    }

    /** Applies a lifecycle update reported by a wrapper or a service. */
    public void updateState(UUID uniqueId, ServiceState state, int exitCode) {
        services.byId(uniqueId).ifPresent(service -> {
            transition(service, state);
            if (state.isTerminal()) {
                if (state == ServiceState.CRASHED) {
                    LOGGER.warn("{} exited unexpectedly with code {}", service.name(), exitCode);
                }
                forget(uniqueId);
            }
        });
    }

    public void transition(ServiceInfo service, ServiceState state) {
        ServiceState previous = service.state();
        if (previous == state) {
            return;
        }
        service.state(state);
        events.post(new ServiceStateChangedEvent(service, previous, state));
        LOGGER.debug("{}: {} -> {}", service.name(), previous, state);
    }

    /**
     * Drops a service from the registry, releasing its name and port.
     *
     * <p>Public because reconciliation needs it too: when a wrapper reconnects
     * and no longer reports a service the node was holding, that process really
     * is gone and the record has to go with it.
     */
    public void forget(UUID uniqueId) {
        serviceTokens.remove(uniqueId);
        services.remove(uniqueId).ifPresent(service -> {
            events.post(new ServiceRemovedEvent(service));
            LOGGER.info("{} is gone (port {} released)", service.name(), service.port());
        });
    }
}
