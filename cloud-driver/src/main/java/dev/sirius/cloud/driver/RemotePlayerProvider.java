package dev.sirius.cloud.driver;

import dev.sirius.cloud.api.driver.PlayerProvider;
import dev.sirius.cloud.api.player.CloudPlayer;
import dev.sirius.cloud.protocol.connection.NetworkChannel;
import dev.sirius.cloud.protocol.connection.NetworkClient;
import dev.sirius.cloud.protocol.packet.Packet;
import dev.sirius.cloud.protocol.packet.impl.AcknowledgePacket;
import dev.sirius.cloud.protocol.packet.impl.PlayerConnectRequestPacket;
import dev.sirius.cloud.protocol.packet.impl.PlayerKickPacket;
import dev.sirius.cloud.protocol.packet.impl.PlayerListRequestPacket;
import dev.sirius.cloud.protocol.packet.impl.PlayerListResponsePacket;
import dev.sirius.cloud.protocol.packet.impl.PlayerMessagePacket;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link PlayerProvider} over the network.
 *
 * <p>Gives a plugin on one server the same reach the node has: move, message or
 * kick anyone in the cloud without knowing which proxy or server holds them.
 */
final class RemotePlayerProvider implements PlayerProvider {

    private final NetworkClient client;

    /** Last known players, so lookups do not need a round trip. */
    private final Map<UUID, CloudPlayer> cache = new ConcurrentHashMap<>();

    RemotePlayerProvider(NetworkClient client) {
        this.client = client;
    }

    @Override
    public CompletableFuture<Collection<CloudPlayer>> onlinePlayers() {
        return playersOn(null);
    }

    @Override
    public CompletableFuture<Collection<CloudPlayer>> playersOn(String serviceName) {
        return query(new PlayerListRequestPacket(serviceName)).thenApply(packet -> {
            List<CloudPlayer> players = ((PlayerListResponsePacket) packet).players();
            // A full listing is authoritative; a filtered one only adds.
            if (serviceName == null) {
                cache.clear();
            }
            players.forEach(player -> cache.put(player.uniqueId(), player));
            return players;
        });
    }

    @Override
    public Optional<CloudPlayer> cachedPlayer(UUID uniqueId) {
        return Optional.ofNullable(cache.get(uniqueId));
    }

    @Override
    public Optional<CloudPlayer> cachedPlayer(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        return cache.values().stream()
                .filter(player -> player.name().toLowerCase(Locale.ROOT).equals(key))
                .findFirst();
    }

    @Override
    public int onlineCount() {
        return cache.size();
    }

    @Override
    public CompletableFuture<Void> connect(UUID uniqueId, String serviceName) {
        return acknowledged(new PlayerConnectRequestPacket(uniqueId, serviceName));
    }

    @Override
    public CompletableFuture<Void> connectToGroup(UUID uniqueId, String groupName) {
        // The node distinguishes a service name from a group name and balances
        // across a group, so the same packet covers both.
        return acknowledged(new PlayerConnectRequestPacket(uniqueId, groupName));
    }

    @Override
    public CompletableFuture<Void> sendMessage(UUID uniqueId, String message) {
        return acknowledged(new PlayerMessagePacket(uniqueId, message));
    }

    @Override
    public CompletableFuture<Void> broadcast(String message) {
        return acknowledged(new PlayerMessagePacket(null, message));
    }

    @Override
    public CompletableFuture<Void> kick(UUID uniqueId, String reason) {
        return acknowledged(new PlayerKickPacket(uniqueId, reason));
    }

    void cachePlayer(CloudPlayer player) {
        cache.put(player.uniqueId(), player);
    }

    void evictPlayer(UUID uniqueId) {
        cache.remove(uniqueId);
    }

    /**
     * Sends an operation and completes when the node says what happened.
     *
     * <p>A query rather than a bare send, so that "that player is not online"
     * or "their proxy is not connected" reaches the caller. Completing as soon
     * as the bytes left would report success for every request, including the
     * ones the node cannot carry out.
     */
    private CompletableFuture<Void> acknowledged(Packet packet) {
        Optional<NetworkChannel> channel = client.channel();
        if (channel.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Not connected to the node"));
        }
        return channel.get().query(packet).thenAccept(reply -> {
            if (reply instanceof AcknowledgePacket ack && !ack.success()) {
                throw new IllegalStateException(ack.message());
            }
        });
    }

    private CompletableFuture<Packet> query(Packet packet) {
        Optional<NetworkChannel> channel = client.channel();
        if (channel.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Not connected to the node"));
        }
        return channel.get().query(packet);
    }
}
