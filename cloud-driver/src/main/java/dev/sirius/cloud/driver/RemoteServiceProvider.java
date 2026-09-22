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

    /** Last known state, so lookups do not need a round trip. */
    private final Map<UUID, ServiceInfo> cache = new ConcurrentHashMap<>();

    RemoteServiceProvider(NetworkClient client) {
        this.client = client;
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

    /** Updated by the driver when the node pushes state changes. */
    void updateCache(ServiceInfo service) {
        cache.put(service.uniqueId(), service);
    }

    void evictFromCache(UUID uniqueId) {
        cache.remove(uniqueId);
    }

    private CompletableFuture<Packet> query(Packet packet) {
        Optional<NetworkChannel> channel = client.channel();
        if (channel.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Not connected to the node"));
        }
        return channel.get().query(packet);
    }
}
