package dev.sirius.cloud.api.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
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

    /**
     * When the current state was entered.
     *
     * <p>Separate from {@link #creationTime} because timeouts are about how long
     * a service has been <em>stuck</em>, not how old it is: a service adopted
     * after a node restart re-enters {@code STARTING} long after it was created,
     * and measuring from creation would kill it on sight.
     */
    private long stateChangedAt;

    /**
     * What the service says about itself - {@code state=INGAME}, {@code map=Desert}.
     *
     * <p>The cloud never interprets these apart from the reserved {@code cloud:}
     * prefix, which only the node may write. Sign walls and matchmaking are
     * built on this: they need to know which servers are joinable, and only the
     * game running on a server can say.
     */
    private Map<String, String> properties = new LinkedHashMap<>();

    /** Reported by the service itself; -1 while unknown or not applicable. */
    private double tps = -1;
    private double mspt = -1;
    private int heapUsedMb = -1;
    private int heapMaxMb = -1;

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
        this.stateChangedAt = creationTime;
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
        if (this.state != state) {
            this.stateChangedAt = System.currentTimeMillis();
        }
        this.state = state;
    }

    /** When the current state began. Falls back to creation for records written before this existed. */
    public long stateSince() {
        return stateChangedAt > 0 ? stateChangedAt : creationTime;
    }

    // ------------------------------------------------------------ properties

    /** A copy: the live map is written from network threads. */
    public synchronized Map<String, String> properties() {
        return properties == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    public synchronized Optional<String> property(String key) {
        return properties == null ? Optional.empty() : Optional.ofNullable(properties.get(key));
    }

    /**
     * Merges a change in. A null or empty value removes the key.
     *
     * @return whether anything actually changed, so callers only announce real updates
     */
    public synchronized boolean applyProperties(Map<String, String> change) {
        if (properties == null) {
            properties = new LinkedHashMap<>();
        }
        boolean changed = false;
        for (Map.Entry<String, String> entry : change.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.isBlank()) {
                continue;
            }
            if (value == null || value.isEmpty()) {
                changed |= properties.remove(key) != null;
            } else {
                changed |= !value.equals(properties.put(key, value));
            }
        }
        return changed;
    }

    // --------------------------------------------------------------- metrics

    public double tps() {
        return tps;
    }

    public double mspt() {
        return mspt;
    }

    public int heapUsedMb() {
        return heapUsedMb;
    }

    public int heapMaxMb() {
        return heapMaxMb;
    }

    public void metrics(double tps, double mspt, int heapUsedMb, int heapMaxMb) {
        this.tps = tps;
        this.mspt = mspt;
        this.heapUsedMb = heapUsedMb;
        this.heapMaxMb = heapMaxMb;
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
