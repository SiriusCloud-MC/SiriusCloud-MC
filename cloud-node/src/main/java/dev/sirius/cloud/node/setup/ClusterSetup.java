package dev.sirius.cloud.node.setup;

import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.driver.config.JsonConfig;
import dev.sirius.cloud.node.cluster.ClusterMember;
import dev.sirius.cloud.node.config.ClusterSettings;
import dev.sirius.cloud.node.config.NodeConfig;
import dev.sirius.cloud.node.console.NodeConsole;
import dev.sirius.cloud.protocol.connection.NetworkClient;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The questions for starting a cluster, or joining one.
 *
 * <p>Both only write configuration: the node switches into cluster mode when
 * it next starts, which is also why neither touches running services.
 */
public final class ClusterSetup {

    private static final CloudLogger LOGGER = CloudLogger.of("Setup");

    private final NodeConsole console;
    private final NodeConfig config;
    private final Path nodeDirectory;

    public ClusterSetup(NodeConsole console, NodeConfig config, Path nodeDirectory) {
        this.console = console;
        this.config = config;
        this.nodeDirectory = nodeDirectory;
    }

    /** This node becomes the first member of a new cluster, keeping all its data. */
    public void found() throws IOException {
        console.heading("Start a cluster");
        console.print("  This node becomes the cluster's first member and leader, and keeps its groups");
        console.print("  and data. Add more nodes afterwards with 'cluster add'.");
        console.print("");

        ClusterSettings.Member self = askSelf();
        ClusterSettings cluster = config.cluster();
        cluster.enabled(true);
        String secret = cluster.generateSecret();
        cluster.members().clear();
        cluster.putMember(self);
        JsonConfig.save(nodeDirectory.resolve("config.json"), config);
        ClusterMember.markFounded(nodeDirectory);

        console.print("");
        LOGGER.info("Cluster secret: {}", secret);
        LOGGER.info("New nodes ask for it when they join. It is kept in node/config.json.");
        LOGGER.info("Restart this node to switch to cluster mode ('shutdown', then start it again).");
        LOGGER.info("Services keep running while it restarts.");
    }

    /**
     * This node joins an existing cluster. It takes the cluster's groups,
     * modules' data and shared settings from the leader, replacing its own.
     */
    public void join() throws IOException {
        console.heading("Join a cluster");
        console.print("  The leader sends this node everything it needs: groups, module data and the");
        console.print("  shared settings. Anything this node had of its own is replaced.");
        console.print("");

        String secret = console.ask("Cluster secret (shown by 'cluster status' on the leader)", "");
        if (secret.isBlank()) {
            LOGGER.warn("No secret given; not joining.");
            return;
        }
        ClusterSettings.Member self = askSelf();
        ClusterSettings cluster = config.cluster();
        cluster.enabled(true);
        cluster.secret(secret.trim());
        cluster.members().clear();
        cluster.putMember(self);
        JsonConfig.save(nodeDirectory.resolve("config.json"), config);

        console.print("");
        LOGGER.info("Start this node, then on the cluster's leader run:");
        LOGGER.info("  cluster add {} {} {}", self.name(), self.cluster(), self.clients());
        LOGGER.info("It joins as soon as the leader reaches it.");
    }

    private ClusterSettings.Member askSelf() {
        String host = console.ask("Address other nodes reach this machine on", config.connectAddress());
        int clusterPort = console.askInt("Port for traffic between nodes", config.port() + 1, 1, 65535);
        if ("127.0.0.1".equals(config.connectAddress()) || "localhost".equalsIgnoreCase(config.connectAddress())) {
            console.print("  Note: wrappers are told to reach this node on " + config.connectAddress()
                    + ", which only works on this machine.");
            console.print("  Change it with 'setup node' if wrappers run elsewhere.");
        }
        return new ClusterSettings.Member(config.nodeName(), NetworkClient.endpoint(host, clusterPort),
                NetworkClient.endpoint(config.connectAddress(), config.port()));
    }
}
