package dev.sirius.cloud.driver;

import dev.sirius.cloud.api.driver.NodeProvider;
import dev.sirius.cloud.api.node.NodeInfo;
import dev.sirius.cloud.api.node.WrapperInfo;
import dev.sirius.cloud.protocol.connection.NetworkChannel;
import dev.sirius.cloud.protocol.connection.NetworkClient;
import dev.sirius.cloud.protocol.packet.Packet;
import dev.sirius.cloud.protocol.packet.impl.NodeInfoRequestPacket;
import dev.sirius.cloud.protocol.packet.impl.NodeInfoResponsePacket;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** {@link NodeProvider} implemented as a query against the node. */
final class RemoteNodeProvider implements NodeProvider {

    private final NetworkClient client;

    /** Last known wrappers, so lookups do not need a round trip. */
    private final Map<String, WrapperInfo> wrapperCache = new ConcurrentHashMap<>();

    RemoteNodeProvider(NetworkClient client) {
        this.client = client;
    }

    @Override
    public CompletableFuture<NodeInfo> info() {
        return query().thenApply(NodeInfoResponsePacket::node);
    }

    @Override
    public CompletableFuture<Collection<NodeInfo>> nodes() {
        return query().thenApply(response -> List.copyOf(response.nodes()));
    }

    @Override
    public CompletableFuture<Collection<WrapperInfo>> wrappers() {
        return query().thenApply(response -> {
            List<WrapperInfo> wrappers = response.wrappers();
            // Authoritative: a wrapper missing from the reply has gone away, so
            // the cache is replaced rather than added to.
            wrapperCache.clear();
            wrappers.forEach(wrapper -> wrapperCache.put(wrapper.name().toLowerCase(Locale.ROOT), wrapper));
            return List.copyOf(wrappers);
        });
    }

    @Override
    public Optional<WrapperInfo> cachedWrapper(String name) {
        return Optional.ofNullable(wrapperCache.get(name.toLowerCase(Locale.ROOT)));
    }

    private CompletableFuture<NodeInfoResponsePacket> query() {
        Optional<NetworkChannel> channel = client.channel();
        if (channel.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Not connected to the node"));
        }
        return channel.get().query(new NodeInfoRequestPacket())
                .thenApply(packet -> (NodeInfoResponsePacket) packet);
    }
}
