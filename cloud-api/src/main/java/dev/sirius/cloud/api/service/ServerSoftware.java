package dev.sirius.cloud.api.service;

import java.util.Locale;
import java.util.Optional;

/**
 * What a service runs.
 *
 * <p>The distinction that matters is {@link #runsCloudPlugin()}. Paper and its
 * forks load the cloud's Bukkit plugin, which reports readiness, players and
 * health. Fabric does not: its services are declared ready from their log, and
 * the node knows their players only through the proxy - enough to route to
 * them, not enough to autoscale on.
 */
public enum ServerSoftware {

    PAPER("paper", ServiceType.SERVER, true),
    PURPUR("purpur", ServiceType.SERVER, true),
    /** Paper with regionised multithreading. Plugins must declare support, and the cloud's does. */
    FOLIA("folia", ServiceType.SERVER, true),
    FABRIC("fabric", ServiceType.SERVER, false),
    VELOCITY("velocity", ServiceType.PROXY, true);

    private final String id;
    private final ServiceType type;
    private final boolean runsCloudPlugin;

    ServerSoftware(String id, ServiceType type, boolean runsCloudPlugin) {
        this.id = id;
        this.type = type;
        this.runsCloudPlugin = runsCloudPlugin;
    }

    public String id() {
        return id;
    }

    public ServiceType type() {
        return type;
    }

    public boolean runsCloudPlugin() {
        return runsCloudPlugin;
    }

    /** Paper and its forks, which share Paper's configuration files. */
    public boolean isPaperFamily() {
        return this == PAPER || this == PURPUR || this == FOLIA;
    }

    public static Optional<ServerSoftware> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String wanted = id.trim().toLowerCase(Locale.ROOT);
        for (ServerSoftware software : values()) {
            if (software.id.equals(wanted)) {
                return Optional.of(software);
            }
        }
        return Optional.empty();
    }

    /** What a group of this type runs when it does not say. */
    public static ServerSoftware defaultFor(ServiceType type) {
        return type == ServiceType.PROXY ? VELOCITY : PAPER;
    }
}
