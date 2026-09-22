package dev.sirius.cloud.api.service;

import java.util.UUID;

/**
 * A snapshot of one service. Passed over the wire as JSON, so this stays a
 * plain mutable POJO with a no-arg constructor.
 */
public final class ServiceInfo {

    private ServiceId serviceId;
    private ServiceType type;
    private String wrapperName;
    private String host;
    private int port;
    private int memory;
    private ServiceState state;
    private int playerCount;
    private int maxPlayers;
    private long creationTime;

    /** Copied from the group, so a proxy can route without knowing group definitions. */
    private boolean fallback;

    /** Required by the JSON codec. */
    @SuppressWarnings("unused")
    ServiceInfo() {
    }

    public ServiceInfo(ServiceId serviceId,
                       ServiceType type,
                       String wrapperName,
                       String host,
                       int port,
                       int memory,
                       int maxPlayers) {
        this.serviceId = serviceId;
        this.type = type;
        this.wrapperName = wrapperName;
        this.host = host;
        this.port = port;
        this.memory = memory;
        this.maxPlayers = maxPlayers;
        this.state = ServiceState.PREPARED;
        this.creationTime = System.currentTimeMillis();
    }

    public ServiceId serviceId() {
        return serviceId;
    }

    public UUID uniqueId() {
        return serviceId.uniqueId();
    }

    public String name() {
        return serviceId.name();
    }

    public String groupName() {
        return serviceId.groupName();
    }

    public ServiceType type() {
        return type;
    }

    public String wrapperName() {
        return wrapperName;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public int memory() {
        return memory;
    }

    public ServiceState state() {
        return state;
    }

    public void state(ServiceState state) {
        this.state = state;
    }

    public int playerCount() {
        return playerCount;
    }

    public void playerCount(int playerCount) {
        this.playerCount = playerCount;
    }

    public int maxPlayers() {
        return maxPlayers;
    }

    public boolean fallback() {
        return fallback;
    }

    public void fallback(boolean fallback) {
        this.fallback = fallback;
    }

    public void maxPlayers(int maxPlayers) {
        this.maxPlayers = maxPlayers;
    }

    public long creationTime() {
        return creationTime;
    }

    public long uptimeMillis() {
        return System.currentTimeMillis() - creationTime;
    }

    @Override
    public String toString() {
        return name() + "{" + state + " @" + host + ":" + port + " on " + wrapperName + "}";
    }
}
