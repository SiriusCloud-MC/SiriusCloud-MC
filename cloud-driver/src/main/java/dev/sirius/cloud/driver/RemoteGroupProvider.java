package dev.sirius.cloud.driver;

import dev.sirius.cloud.api.driver.GroupProvider;
import dev.sirius.cloud.api.group.ServiceGroup;
import dev.sirius.cloud.protocol.connection.NetworkChannel;
import dev.sirius.cloud.protocol.connection.NetworkClient;
import dev.sirius.cloud.protocol.packet.impl.GroupListRequestPacket;
import dev.sirius.cloud.protocol.packet.impl.GroupListResponsePacket;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** {@link GroupProvider} implemented as queries against the node. */
final class RemoteGroupProvider implements GroupProvider {

    private final NetworkClient client;
    private final Map<String, ServiceGroup> cache = new ConcurrentHashMap<>();

    RemoteGroupProvider(NetworkClient client) {
        this.client = client;
    }

    @Override
    public CompletableFuture<Collection<ServiceGroup>> groups() {
        Optional<NetworkChannel> channel = client.channel();
        if (channel.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Not connected to the node"));
        }
        return channel.get().query(new GroupListRequestPacket()).thenApply(packet -> {
            List<ServiceGroup> groups = ((GroupListResponsePacket) packet).groups();
            groups.forEach(group -> cache.put(group.name().toLowerCase(Locale.ROOT), group));
            return groups;
        });
    }

    @Override
    public Optional<ServiceGroup> cachedGroup(String name) {
        return Optional.ofNullable(cache.get(name.toLowerCase(Locale.ROOT)));
    }
}
