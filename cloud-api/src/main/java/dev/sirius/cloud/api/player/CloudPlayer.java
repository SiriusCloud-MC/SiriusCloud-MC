package dev.sirius.cloud.api.player;

import java.util.Optional;
import java.util.UUID;

/**
 * A player online somewhere in the cloud.
 *
 * <p>The proxy a player entered through is the authority on their existence;
 * the backend they are on is whatever the proxy last routed them to. Both are
 * recorded because they answer different questions: which proxy can act on
 * this player, and which server are they actually playing on.
 *
 * <p>Passed over the wire as JSON, so this stays a plain mutable POJO.
 */
public final class CloudPlayer {

    private UUID uniqueId;
    private String name;

    /** The proxy this player is connected through. Never null while online. */
    private UUID proxyId;
    private String proxyName;

    /** The backend they are on, or null between servers. */
    private UUID serverId;
    private String serverName;

    private String address;
    private long connectedSince;

    /** Required by the JSON codec. */
    @SuppressWarnings("unused")
    CloudPlayer() {
    }

    public CloudPlayer(UUID uniqueId, String name, UUID proxyId, String proxyName, String address) {
        this.uniqueId = uniqueId;
        this.name = name;
        this.proxyId = proxyId;
        this.proxyName = proxyName;
        this.address = address;
        this.connectedSince = System.currentTimeMillis();
    }

    public UUID uniqueId() {
        return uniqueId;
    }

    public String name() {
        return name;
    }

    public UUID proxyId() {
        return proxyId;
    }

    public String proxyName() {
        return proxyName;
    }

    public Optional<UUID> serverId() {
        return Optional.ofNullable(serverId);
    }

    public Optional<String> serverName() {
        return Optional.ofNullable(serverName);
    }

    public void server(UUID serverId, String serverName) {
        this.serverId = serverId;
        this.serverName = serverName;
    }

    public String address() {
        return address;
    }

    public long connectedSince() {
        return connectedSince;
    }

    public long onlineMillis() {
        return System.currentTimeMillis() - connectedSince;
    }

    @Override
    public String toString() {
        return name + "{" + (serverName == null ? "connecting" : serverName) + " via " + proxyName + "}";
    }
}
