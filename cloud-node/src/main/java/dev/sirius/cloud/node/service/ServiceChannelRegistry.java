package dev.sirius.cloud.node.service;

import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.api.service.ServiceType;
import dev.sirius.cloud.protocol.connection.NetworkChannel;
import dev.sirius.cloud.protocol.packet.impl.ChannelMessagePacket;
import dev.sirius.cloud.protocol.packet.Packet;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The live connections of running services, so the node can push to them.
 *
 * <p>Needed because proxies are told about backend servers as they appear;
 * until now nothing kept hold of a service's channel after its handshake.
 */
public final class ServiceChannelRegistry {

    private final Map<UUID, NetworkChannel> channels = new ConcurrentHashMap<>();

    /** Messaging channels each service listens on, lowercased. */
    private final Map<UUID, Set<String>> subscriptions = new ConcurrentHashMap<>();

    public void register(UUID serviceId, NetworkChannel channel) {
        channels.put(serviceId, channel);
    }

    public void unregister(UUID serviceId) {
        channels.remove(serviceId);
        subscriptions.remove(serviceId);
    }

    /** Replaces the set of messaging channels a service listens on. */
    public void subscriptions(UUID serviceId, Collection<String> subscribed) {
        Set<String> set = ConcurrentHashMap.newKeySet();
        subscribed.forEach(channel -> set.add(channel.toLowerCase(Locale.ROOT)));
        subscriptions.put(serviceId, set);
    }

    /**
     * Delivers a channel message to every service subscribed to its channel.
     *
     * <p>Filtered by subscription rather than broadcast: a private message or a
     * single player's command result has no business arriving at every server
     * in the cloud, and a server with no handler for a channel only has the
     * traffic to throw away.
     *
     * @param exclude the publisher, which never receives its own message
     */
    public void publish(ChannelMessagePacket packet, UUID exclude) {
        String channel = packet.channel() == null ? "" : packet.channel().toLowerCase(Locale.ROOT);
        for (Map.Entry<UUID, NetworkChannel> entry : channels.entrySet()) {
            if (exclude != null && exclude.equals(entry.getKey())) {
                continue;
            }
            Set<String> subscribed = subscriptions.get(entry.getKey());
            if (subscribed != null && subscribed.contains(channel)) {
                entry.getValue().send(packet);
            }
        }
    }

    /** Sends one packet to one service. */
    public boolean sendTo(UUID serviceId, Packet packet) {
        NetworkChannel channel = channels.get(serviceId);
        if (channel == null) {
            return false;
        }
        channel.send(packet);
        return true;
    }

    public Optional<NetworkChannel> byService(UUID serviceId) {
        return Optional.ofNullable(channels.get(serviceId));
    }

    /**
     * Sends a packet to every connected proxy.
     *
     * @param services registry used to tell proxies from backend servers
     */
    public void broadcastToProxies(ServiceRegistry services, Packet packet) {
        for (Map.Entry<UUID, NetworkChannel> entry : channels.entrySet()) {
            services.byId(entry.getKey())
                    .filter(service -> service.type() == ServiceType.PROXY)
                    .ifPresent(service -> entry.getValue().send(packet));
        }
    }

    /**
     * Sends a packet to every connected service except one.
     *
     * <p>The exclusion is what stops a publisher receiving its own message
     * back, which would make the ordinary "apply here, then tell everyone
     * else" pattern apply twice on the originating server.
     *
     * @param exclude service not to send to, or null to send to all
     */
    public void broadcastToServices(Packet packet, UUID exclude) {
        for (Map.Entry<UUID, NetworkChannel> entry : channels.entrySet()) {
            if (exclude != null && exclude.equals(entry.getKey())) {
                continue;
            }
            entry.getValue().send(packet);
        }
    }

    public Collection<UUID> connectedServices() {
        return List.copyOf(channels.keySet());
    }

    /** Backend servers currently reachable, for seeding a newly connected proxy. */
    public static List<ServiceInfo> reachableServers(ServiceRegistry services) {
        return services.all().stream()
                .filter(service -> service.type() == ServiceType.SERVER)
                .filter(service -> service.state() == dev.sirius.cloud.api.service.ServiceState.RUNNING)
                .toList();
    }
}
