package dev.sirius.cloud.node.config;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;
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

    /** Persistent storage; see {@link DatabaseSettings}. */
    private DatabaseSettings database = new DatabaseSettings();

    /** Running several nodes as one; see {@link ClusterSettings}. */
    private ClusterSettings cluster = new ClusterSettings();

    /**
     * Settings that describe the network rather than this machine, so every
     * node of a cluster must hold the same values. The leader replicates
     * these; name, addresses and ports stay each node's own.
     */
    private static final List<String> SHARED = List.of(
            "secret", "forwardingSecret", "apiSecret", "apiReadOnly", "maxMemory", "minimumPaperVersion",
            "database");

    public ClusterSettings cluster() {
        if (cluster == null) {
            cluster = new ClusterSettings();
        }
        return cluster;
    }

    /** The shared settings as the leader sends them. See {@link #SHARED}. */
    public JsonObject sharedSettings(Gson gson) {
        forwardingSecret();
        apiSecret();
        JsonObject all = gson.toJsonTree(this).getAsJsonObject();
        JsonObject shared = new JsonObject();
        for (String key : SHARED) {
            if (all.has(key)) {
                shared.add(key, all.get(key));
            }
        }
        JsonObject clusterShared = new JsonObject();
        clusterShared.addProperty("secret", cluster().secret());
        clusterShared.add("members", gson.toJsonTree(cluster().members()));
        shared.add("cluster", clusterShared);
        return shared;
    }

    /** Takes the leader's shared settings, keeping this node's own. */
    public void adoptShared(JsonObject shared, Gson gson) {
        NodeConfig incoming = gson.fromJson(shared, NodeConfig.class);
        if (shared.has("secret")) {
            secret = incoming.secret;
        }
        if (shared.has("forwardingSecret")) {
            forwardingSecret = incoming.forwardingSecret;
        }
        if (shared.has("apiSecret")) {
            apiSecret = incoming.apiSecret;
        }
        if (shared.has("apiReadOnly")) {
            apiReadOnly = incoming.apiReadOnly;
        }
        if (shared.has("maxMemory")) {
            maxMemory = incoming.maxMemory;
        }
        if (shared.has("minimumPaperVersion")) {
            minimumPaperVersion = incoming.minimumPaperVersion;
        }
        if (shared.has("database")) {
            database = incoming.database;
        }
        JsonElement clusterShared = shared.get("cluster");
        if (clusterShared != null && clusterShared.isJsonObject()) {
            ClusterSettings parsed = gson.fromJson(clusterShared, ClusterSettings.class);
            cluster().secret(parsed.secret());
            cluster().members().clear();
            cluster().members().addAll(parsed.members());
        }
    }

    public DatabaseSettings database() {
        if (database == null) {
            database = new DatabaseSettings();
        }
        return database;
    }

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
