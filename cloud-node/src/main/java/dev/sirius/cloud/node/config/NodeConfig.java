package dev.sirius.cloud.node.config;

import java.util.UUID;

/** {@code node/config.json}. */
public final class NodeConfig {

    private String nodeName = "node-1";

    /** Interface the listener binds to. {@code 0.0.0.0} accepts remote wrappers. */
    private String bindAddress = "0.0.0.0";

    /**
     * Address wrappers and services are told to connect back to.
     *
     * <p>Separate from {@link #bindAddress} on purpose: binding to
     * {@code 0.0.0.0} is right, but handing {@code 0.0.0.0} to a service as a
     * connect target is not.
     */
    private String connectAddress = "127.0.0.1";

    private int port = 1420;

    /** Shared secret wrappers and API clients authenticate with. */
    private String secret = UUID.randomUUID().toString();

    /** Memory the node is willing to see committed across all services, in MB. */
    private int maxMemory = 4096;

    /**
     * Oldest Paper version offered by the {@code versions} command.
     *
     * <p>Purely a display filter — a group may still pin anything PaperMC
     * publishes. It exists so the listing shows the range you actually run
     * instead of a decade of history.
     */
    private String minimumPaperVersion = "1.21.1";

    /**
     * Whether first-run setup has been offered.
     *
     * <p>Tracked separately from "are there any groups", so declining the offer
     * is remembered instead of being asked again on every start.
     */
    private boolean setupCompleted = false;

    private boolean debug = false;

    public boolean setupCompleted() {
        return setupCompleted;
    }

    public void setupCompleted(boolean setupCompleted) {
        this.setupCompleted = setupCompleted;
    }

    public String minimumPaperVersion() {
        return minimumPaperVersion == null || minimumPaperVersion.isBlank()
                ? "1.21.1"
                : minimumPaperVersion;
    }

    public String nodeName() {
        return nodeName;
    }

    public String bindAddress() {
        return bindAddress;
    }

    public String connectAddress() {
        return connectAddress;
    }

    public int port() {
        return port;
    }

    public String secret() {
        return secret;
    }

    public int maxMemory() {
        return maxMemory;
    }

    public boolean debug() {
        return debug;
    }
}
