package dev.sirius.cloud.node.player;

import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.player.CloudPlayer;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.api.service.ServiceState;
import dev.sirius.cloud.api.service.ServiceType;
import dev.sirius.cloud.node.service.ServiceChannelRegistry;
import dev.sirius.cloud.node.service.ServiceRegistry;
import dev.sirius.cloud.protocol.connection.NetworkChannel;
import dev.sirius.cloud.protocol.packet.Packet;
import dev.sirius.cloud.protocol.packet.impl.PlayerConnectRequestPacket;
import dev.sirius.cloud.protocol.packet.impl.PlayerKickPacket;
import dev.sirius.cloud.protocol.packet.impl.PlayerMessagePacket;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Acts on players wherever they are.
 *
 * <p>Every operation resolves to "which proxy holds this player" and sends it
 * one packet. Callers never need to know which proxy that is, which is what
 * makes a single cloud out of several of them.
 */
public final class PlayerManager {

    private static final CloudLogger LOGGER = CloudLogger.of("Players");

    /**
     * How long a transfer counts against a service's load.
     *
     * <p>Long enough to cover a connection handshake and a world load, short
     * enough that a transfer which silently failed stops skewing the balance.
     */
    private static final long PENDING_TRANSFER_MILLIS = 10_000;

    private final PlayerRegistry players;
    private final ServiceRegistry services;
    private final ServiceChannelRegistry serviceChannels;

    /**
     * Transfers sent but not yet reflected in a player count.
     *
     * <p>Service player counts arrive on a heartbeat, so they are up to ten
     * seconds stale. Balancing on them alone means a burst of joins all sees
     * the same "emptiest" server and piles onto it, which is the exact failure
     * least-loaded balancing exists to avoid. Counting what is already on its
     * way closes the window.
     */
    private final Map<UUID, Deque<Long>> pendingTransfers = new ConcurrentHashMap<>();

    public PlayerManager(PlayerRegistry players,
                         ServiceRegistry services,
                         ServiceChannelRegistry serviceChannels) {
        this.players = players;
        this.services = services;
        this.serviceChannels = serviceChannels;
    }

    public CompletableFuture<Void> connect(UUID playerId, String serviceName) {
        Optional<ServiceInfo> target = services.byName(serviceName);
        if (target.isEmpty()) {
            return failed("No service named '" + serviceName + "'");
        }
        if (target.get().type() == ServiceType.PROXY) {
            return failed(serviceName + " is a proxy; players cannot be sent to one");
        }
        if (target.get().state() != ServiceState.RUNNING) {
            return failed(serviceName + " is " + target.get().state() + ", not running");
        }
        recordTransfer(target.get().uniqueId());
        return send(playerId, player -> new PlayerConnectRequestPacket(playerId, target.get().name()));
    }

    /**
     * Sends a player to the least-loaded running service of a group.
     *
     * <p>Balancing happens here rather than on the proxy so that every caller
     * gets the same behaviour, and so a proxy needs no notion of groups.
     */
    public CompletableFuture<Void> connectToGroup(UUID playerId, String groupName) {
        Optional<ServiceInfo> target = services.ofGroup(groupName).stream()
                .filter(service -> service.state() == ServiceState.RUNNING)
                .filter(service -> service.type() == ServiceType.SERVER)
                .min(Comparator.comparingInt(this::effectiveLoad));

        if (target.isEmpty()) {
            return failed("No running service of group '" + groupName + "'");
        }
        return connect(playerId, target.get().name());
    }

    /** A service's reported players, plus the transfers already heading for it. */
    private int effectiveLoad(ServiceInfo service) {
        return service.playerCount() + pendingCount(service.uniqueId());
    }

    private int pendingCount(UUID serviceId) {
        Deque<Long> pending = pendingTransfers.get(serviceId);
        if (pending == null) {
            return 0;
        }
        long cutoff = System.currentTimeMillis() - PENDING_TRANSFER_MILLIS;
        // Expired entries are dropped as they are counted rather than on a
        // timer: the only moment the count matters is when it is read.
        synchronized (pending) {
            while (!pending.isEmpty() && pending.peekFirst() < cutoff) {
                pending.removeFirst();
            }
            return pending.size();
        }
    }

    private void recordTransfer(UUID serviceId) {
        Deque<Long> pending = pendingTransfers.computeIfAbsent(
                serviceId, key -> new ArrayDeque<>());
        synchronized (pending) {
            pending.addLast(System.currentTimeMillis());
        }
    }

    /**
     * Stops counting a transfer once the player has actually landed.
     *
     * <p>Called when a proxy reports the switch, so a service that filled up
     * quickly is not double-counted: its own player count now includes them.
     */
    public void transferCompleted(UUID serviceId) {
        Deque<Long> pending = pendingTransfers.get(serviceId);
        if (pending == null) {
            return;
        }
        synchronized (pending) {
            pending.pollFirst();
        }
    }

    public CompletableFuture<Void> sendMessage(UUID playerId, String message) {
        return send(playerId, player -> new PlayerMessagePacket(playerId, message));
    }

    public CompletableFuture<Void> kick(UUID playerId, String reason) {
        return send(playerId, player -> new PlayerKickPacket(playerId, reason));
    }

    /** Message to everyone, on every proxy. */
    public CompletableFuture<Void> broadcast(String message) {
        serviceChannels.broadcastToProxies(services, new PlayerMessagePacket(null, message));
        LOGGER.info("Broadcast to {} player(s): {}", players.count(), message);
        return CompletableFuture.completedFuture(null);
    }

    /** Resolves the player's proxy and sends it a packet. */
    private CompletableFuture<Void> send(UUID playerId,
                                         java.util.function.Function<CloudPlayer, Packet> packet) {
        Optional<CloudPlayer> player = players.byId(playerId);
        if (player.isEmpty()) {
            return failed("That player is not online");
        }

        Optional<NetworkChannel> channel = serviceChannels.byService(player.get().proxyId());
        if (channel.isEmpty()) {
            // Their proxy dropped but the disconnect has not arrived yet.
            return failed("The proxy holding " + player.get().name() + " is not connected");
        }

        channel.get().send(packet.apply(player.get()));
        return CompletableFuture.completedFuture(null);
    }

    private static CompletableFuture<Void> failed(String message) {
        return CompletableFuture.failedFuture(new IllegalStateException(message));
    }
}
