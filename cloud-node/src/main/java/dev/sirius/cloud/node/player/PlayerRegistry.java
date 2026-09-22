package dev.sirius.cloud.node.player;

import dev.sirius.cloud.api.player.CloudPlayer;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Who is online anywhere in the cloud, and where. */
public final class PlayerRegistry {

    private final Map<UUID, CloudPlayer> byId = new ConcurrentHashMap<>();

    /** Lowercased, so lookups work however the operator types the name. */
    private final Map<String, UUID> byName = new ConcurrentHashMap<>();

    public void add(CloudPlayer player) {
        byId.put(player.uniqueId(), player);
        byName.put(player.name().toLowerCase(Locale.ROOT), player.uniqueId());
    }

    public Optional<CloudPlayer> remove(UUID uniqueId) {
        CloudPlayer player = byId.remove(uniqueId);
        if (player == null) {
            return Optional.empty();
        }
        // Only drop the name index if it still points at this player: a
        // reconnect can register the same name under a new entry before the
        // old disconnect arrives, and removing blindly would unindex the
        // live player.
        byName.remove(player.name().toLowerCase(Locale.ROOT), uniqueId);
        return Optional.of(player);
    }

    public Optional<CloudPlayer> byId(UUID uniqueId) {
        return Optional.ofNullable(byId.get(uniqueId));
    }

    public Optional<CloudPlayer> byName(String name) {
        UUID uniqueId = byName.get(name.toLowerCase(Locale.ROOT));
        return uniqueId == null ? Optional.empty() : byId(uniqueId);
    }

    public Collection<CloudPlayer> all() {
        return List.copyOf(byId.values());
    }

    public List<CloudPlayer> onService(String serviceName) {
        return byId.values().stream()
                .filter(player -> player.serverName()
                        .map(name -> name.equalsIgnoreCase(serviceName))
                        .orElse(false))
                .toList();
    }

    public List<CloudPlayer> onProxy(UUID proxyId) {
        return byId.values().stream()
                .filter(player -> proxyId.equals(player.proxyId()))
                .toList();
    }

    public int count() {
        return byId.size();
    }

    /**
     * Replaces everything known about one proxy's players.
     *
     * <p>Used for the snapshot a proxy sends on connect. Replacing rather than
     * merging is the point: it both recovers players the node never saw log in
     * (because the node restarted under them) and evicts ghosts left by a proxy
     * that died without saying goodbye.
     *
     * @return players that were dropped because they are no longer there
     */
    public List<CloudPlayer> replaceProxyPlayers(UUID proxyId, Collection<CloudPlayer> current) {
        List<CloudPlayer> stale = onProxy(proxyId);
        stale.forEach(player -> remove(player.uniqueId()));
        current.forEach(this::add);
        return stale;
    }

    /** Drops every player of a proxy that has gone away. */
    public List<CloudPlayer> removeProxyPlayers(UUID proxyId) {
        List<CloudPlayer> affected = onProxy(proxyId);
        affected.forEach(player -> remove(player.uniqueId()));
        return affected;
    }
}
