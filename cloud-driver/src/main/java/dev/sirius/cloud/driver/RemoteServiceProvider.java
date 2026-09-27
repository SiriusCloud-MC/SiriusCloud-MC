package dev.sirius.cloud.driver;

import dev.sirius.cloud.api.driver.ServiceProvider;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.protocol.connection.NetworkChannel;
import dev.sirius.cloud.protocol.connection.NetworkClient;
import dev.sirius.cloud.protocol.packet.Packet;
import dev.sirius.cloud.protocol.packet.impl.AcknowledgePacket;
import dev.sirius.cloud.protocol.packet.impl.ConsoleCommandPacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceListRequestPacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceListResponsePacket;
import dev.sirius.cloud.protocol.packet.impl.ServicePropertiesPacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceStartRequestPacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceStartResponsePacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceStopPacket;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** {@link ServiceProvider} implemented as queries against the node. */
final class RemoteServiceProvider implements ServiceProvider {

    private final NetworkClient client;

    /** The service this driver runs inside, or null on a wrapper or tool. */
    private final UUID selfId;

    /**
     * Last known state, so lookups do not need a round trip.
     *
     * <p>Kept live by the node's pushes rather than refreshed by queries, so it
     * is as current as the node's own view to within one packet.
     */
    private final Map<UUID, ServiceInfo> cache = new ConcurrentHashMap<>();

    RemoteServiceProvider(NetworkClient client, UUID selfId) {
        this.client = client;
        this.selfId = selfId;
    }

    @Override
    public CompletableFuture<ServiceInfo> startService(String groupName) {
        return query(new ServiceStartRequestPacket(groupName)).thenApply(packet -> {
            ServiceStartResponsePacket response = (ServiceStartResponsePacket) packet;
            if (!response.success()) {
                throw new IllegalStateException("Could not start service: " + response.message());
            }
            cache.put(response.service().uniqueId(), response.service());
            return response.service();
        });
    }

    @Override
    public CompletableFuture<Void> stopService(UUID uniqueId) {
        return query(new ServiceStopPacket(uniqueId, false)).thenAccept(packet -> {
            AcknowledgePacket ack = (AcknowledgePacket) packet;
            if (!ack.success()) {
                throw new IllegalStateException("Could not stop service: " + ack.message());
            }
            cache.remove(uniqueId);
        });
    }

    @Override
    public CompletableFuture<Collection<ServiceInfo>> services() {
        return servicesOfGroup(null);
    }

    @Override
    public CompletableFuture<Collection<ServiceInfo>> servicesOfGroup(String groupName) {
        return query(new ServiceListRequestPacket(groupName)).thenApply(packet -> {
            List<ServiceInfo> services = ((ServiceListResponsePacket) packet).services();
            services.forEach(service -> cache.put(service.uniqueId(), service));
            return services;
        });
    }

    @Override
    public Optional<ServiceInfo> cachedService(UUID uniqueId) {
        return Optional.ofNullable(cache.get(uniqueId));
    }

    @Override
    public Optional<ServiceInfo> cachedService(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        return cache.values().stream()
                .filter(service -> service.serviceId().nameKey().equals(key))
                .findFirst();
    }

    @Override
    public CompletableFuture<Void> dispatchCommand(UUID uniqueId, String command) {
        return query(new ConsoleCommandPacket(uniqueId, command)).thenAccept(packet -> {
        });
    }

    @Override
    public Optional<ServiceInfo> self() {
        return selfId == null ? Optional.empty() : Optional.ofNullable(cache.get(selfId));
    }

    @Override
    public CompletableFuture<Void> updateProperties(UUID uniqueId, Map<String, String> properties) {
        return query(new ServicePropertiesPacket(uniqueId, properties)).thenAccept(packet -> {
            AcknowledgePacket ack = (AcknowledgePacket) packet;
            if (!ack.success()) {
                throw new IllegalStateException(ack.message());
            }
        });
    }

    /**
     * Updated by the driver when the node pushes state changes.
     *
     * @return what the cache held before, so the driver can tell what changed
     */
    ServiceInfo updateCache(ServiceInfo service) {
        return cache.put(service.uniqueId(), service);
    }

    ServiceInfo evictFromCache(UUID uniqueId) {
        return cache.remove(uniqueId);
    }

    private CompletableFuture<Packet> query(Packet packet) {
        Optional<NetworkChannel> channel = client.channel();
        if (channel.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Not connected to the node"));
        }
        return channel.get().query(packet);
    }
}
