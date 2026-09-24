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

    /**
     * Shared secret for Velocity modern forwarding.
     *
     * <p>Separate from {@link #secret}: this one is written into every service
     * directory on every machine, while the cloud secret only ever reaches
     * wrappers. Leaking one should not compromise the other.
     */
    private String forwardingSecret = UUID.randomUUID().toString();

    /**
     * Secret external tools and modules authenticate with.
     *
     * <p>Separate from {@link #secret} because the two grant different things.
     * A wrapper's secret lets a machine register and receive orders to spawn
     * processes; an API client only needs to read state and ask for services.
     * Sharing one value meant a leaked API token could register a fake wrapper
     * and be handed real work, which is a much larger problem than the token
     * was supposed to represent.
     */
    private String apiSecret = UUID.randomUUID().toString();

    /**
     * Whether API clients may only read.
     *
     * <p>Off by default, because the API is how panels and bots do useful
     * things. Turning it on makes every external client observation-only
     * without having to take their credentials away.
     */
    private boolean apiReadOnly = false;

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

    public void nodeName(String nodeName) {
        this.nodeName = nodeName;
    }

    public void bindAddress(String bindAddress) {
        this.bindAddress = bindAddress;
    }

    public void connectAddress(String connectAddress) {
        this.connectAddress = connectAddress;
    }

    public void port(int port) {
        this.port = port;
    }

    public void maxMemory(int maxMemory) {
        this.maxMemory = maxMemory;
    }

    public void minimumPaperVersion(String minimumPaperVersion) {
        this.minimumPaperVersion = minimumPaperVersion;
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

    public String forwardingSecret() {
        if (forwardingSecret == null || forwardingSecret.isBlank()) {
            // Upgrading from a config written before forwarding existed.
            forwardingSecret = UUID.randomUUID().toString();
        }
        return forwardingSecret;
    }

    public String apiSecret() {
        if (apiSecret == null || apiSecret.isBlank()) {
            // Upgrading from a config written before the split. Generating a
            // fresh one is right: falling back to the wrapper secret would
            // silently preserve exactly the behaviour this replaced.
            apiSecret = UUID.randomUUID().toString();
        }
        return apiSecret;
    }

    public boolean apiReadOnly() {
        return apiReadOnly;
    }

    public int maxMemory() {
        return maxMemory;
    }

    public boolean debug() {
        return debug;
    }
}
