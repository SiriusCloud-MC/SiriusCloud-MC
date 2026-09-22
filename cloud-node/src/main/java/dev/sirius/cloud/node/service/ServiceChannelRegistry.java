package dev.sirius.cloud.node.service;

import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.api.service.ServiceType;
import dev.sirius.cloud.protocol.connection.NetworkChannel;
import dev.sirius.cloud.protocol.packet.Packet;

import java.util.Collection;
import java.util.List;
import java.util.Map;
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

    public void register(UUID serviceId, NetworkChannel channel) {
        channels.put(serviceId, channel);
    }

    public void unregister(UUID serviceId) {
        channels.remove(serviceId);
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
