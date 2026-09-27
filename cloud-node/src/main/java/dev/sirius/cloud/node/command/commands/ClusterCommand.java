package dev.sirius.cloud.node.command.commands;

import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.driver.config.JsonConfig;
import dev.sirius.cloud.node.cluster.ClusterMember;
import dev.sirius.cloud.node.command.Command;
import dev.sirius.cloud.node.config.ClusterSettings;
import dev.sirius.cloud.node.config.NodeConfig;
import dev.sirius.cloud.node.console.NodeConsole;
import dev.sirius.cloud.node.setup.ClusterSetup;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Starting, growing and inspecting a cluster.
 *
 * <p>Membership changes are made on the leader, which replicates the member
 * list to every node; that is what keeps every node counting the same
 * majority.
 */
public final class ClusterCommand implements Command {

    private static final CloudLogger LOGGER = CloudLogger.of("Console");

    private final NodeConfig config;
    private final Path nodeDirectory;
    private final NodeConsole console;
    private final Supplier<ClusterMember> member;

    public ClusterCommand(NodeConfig config, Path nodeDirectory, NodeConsole console, Supplier<ClusterMember> member) {
        this.config = config;
        this.nodeDirectory = nodeDirectory;
        this.console = console;
        this.member = member;
    }

    @Override
    public String name() {
        return "cluster";
    }

    @Override
    public String usage() {
        return "cluster [status|init|join|add|remove|promote]";
    }

    @Override
    public String description() {
        return "Runs several nodes as one, with failover";
    }

    @Override
    public void execute(String[] args) {
        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        try {
            switch (action) {
                case "status" -> status();
                case "init" -> init();
                case "join" -> join();
                case "add" -> add(args);
                case "remove" -> remove(args);
                case "promote" -> promote();
                default -> LOGGER.warn("Usage: {}", usage());
            }
        } catch (IOException exception) {
            LOGGER.error("Could not save node/config.json: {}", exception.getMessage());
        }
    }

    private void status() {
        ClusterMember cluster = member.get();
        if (cluster == null) {
            if (config.cluster().enabled()) {
                CloudLogger.raw("Clustering is configured and starts when this node restarts.");
            } else {
                CloudLogger.raw("This node is not clustered.");
                CloudLogger.raw("  cluster init   start a cluster with this node as its first member");
                CloudLogger.raw("  cluster join   join an existing cluster (this node's data is replaced)");
            }
            return;
        }
        ClusterMember.Status status = cluster.status();
        CloudLogger.raw("This node : " + status.self() + " (" + describe(status) + ")");
        CloudLogger.raw("Leader    : " + (status.leader() == null ? "none - an election is due" : status.leader())
                + "   term " + status.term());
        CloudLogger.raw("Data      : revision " + status.revision());
        CloudLogger.raw("Majority  : " + config.cluster().quorum() + " of " + status.members().size());
        CloudLogger.raw("Members   :");
        for (ClusterMember.MemberStatus member : status.members()) {
            String state;
            if (member.self()) {
                state = "this node";
            } else if (!member.connected()) {
                state = "unreachable";
            } else {
                state = "connected, " + (member.lastContactMillisAgo() < 0 ? "silent"
                        : "heard " + member.lastContactMillisAgo() + "ms ago");
            }
            CloudLogger.raw(String.format(Locale.ROOT, "  %-14s %-22s clients %-22s %s%s",
                    member.name(), member.cluster(), member.clients(), state,
                    member.revision() >= 0 && !member.self() ? ", revision " + member.revision() : ""));
        }
        if (status.forced()) {
            CloudLogger.raw("Promoted by hand: leading without a majority until every member is back.");
        }
        if (status.members().size() == 2) {
            CloudLogger.raw("Two members need each other for a majority: if one fails, the other waits.");
            CloudLogger.raw("Add a third for automatic failover, or use 'cluster promote' on the survivor.");
        }
        if (cluster.isLeader()) {
            CloudLogger.raw("Secret    : " + config.cluster().secret());
        }
    }

    private static String describe(ClusterMember.Status status) {
        if (!status.joined()) {
            return "waiting to be added by the leader";
        }
        return switch (status.role()) {
            case LEADER -> "leader";
            case CANDIDATE -> "candidate";
            case FOLLOWER -> "follower";
        };
    }

    private void init() throws IOException {
        if (config.cluster().enabled()) {
            LOGGER.warn("This node is already part of a cluster.");
            return;
        }
        new ClusterSetup(console, config, nodeDirectory).found();
    }

    private void join() throws IOException {
        if (config.cluster().enabled()) {
            LOGGER.warn("This node is already part of a cluster.");
            return;
        }
        console.print("  Joining replaces this node's groups and module data with the cluster's.");
        if (!console.confirm("Continue?", false)) {
            return;
        }
        new ClusterSetup(console, config, nodeDirectory).join();
        LOGGER.info("Restart this node to join ('shutdown', then start it again).");
    }

    private ClusterMember leaderOnly() {
        ClusterMember cluster = member.get();
        if (cluster == null) {
            LOGGER.warn("This node is not running as part of a cluster. Start one with 'cluster init'.");
            return null;
        }
        if (!cluster.isLeader()) {
            LOGGER.warn("Membership changes are made on the leader: {}", cluster.leader().orElse("none right now"));
            return null;
        }
        return cluster;
    }

    private void add(String[] args) throws IOException {
        if (args.length < 4) {
            LOGGER.warn("Usage: cluster add <name> <cluster host:port> <client host:port>");
            LOGGER.warn("The new node prints this line for you when it starts.");
            return;
        }
        ClusterMember cluster = leaderOnly();
        if (cluster == null) {
            return;
        }
        String name = args[1];
        if (!args[2].contains(":") || !args[3].contains(":")) {
            LOGGER.warn("Addresses are host:port, e.g. 10.0.0.2:1421 and 10.0.0.2:1420.");
            return;
        }
        boolean existed = config.cluster().member(name).isPresent();
        if (!ClusterMember.reachable(args[2], 3_000)) {
            LOGGER.warn("Nothing answers on {}. Start '{}' first; it waits to be added and prints this", args[2],
                    name);
            LOGGER.warn("exact command. Adding an unreachable member would cost this cluster its majority.");
            return;
        }
        config.cluster().putMember(new ClusterSettings.Member(name, args[2], args[3]));
        JsonConfig.save(nodeDirectory.resolve("config.json"), config);
        cluster.membersChanged();

        int size = config.cluster().members().size();
        LOGGER.info("{} '{}'. The cluster has {} member(s); a majority is {}.", existed ? "Updated" : "Added", name,
                size, config.cluster().quorum());
        if (size == 2) {
            LOGGER.warn("With two members, each needs the other to keep a leader. If '{}' is not running yet,", name);
            LOGGER.warn("this node steps down until it is. A third member gives automatic failover.");
        }
    }

    private void remove(String[] args) throws IOException {
        if (args.length < 2) {
            LOGGER.warn("Usage: cluster remove <name>");
            return;
        }
        ClusterMember cluster = leaderOnly();
        if (cluster == null) {
            return;
        }
        if (args[1].equalsIgnoreCase(config.nodeName())) {
            LOGGER.warn("The leader cannot remove itself. Shut it down first; another node takes over,");
            LOGGER.warn("and it can be removed from there.");
            return;
        }
        if (!config.cluster().removeMember(args[1])) {
            LOGGER.warn("No member called '{}'.", args[1]);
            return;
        }
        JsonConfig.save(nodeDirectory.resolve("config.json"), config);
        cluster.membersChanged();
        LOGGER.info("Removed '{}'. A majority is now {} of {}.", args[1], config.cluster().quorum(),
                config.cluster().members().size());
    }

    private void promote() {
        ClusterMember cluster = member.get();
        if (cluster == null) {
            LOGGER.warn("This node is not running as part of a cluster.");
            return;
        }
        if (cluster.isLeader()) {
            LOGGER.info("This node is already the leader.");
            return;
        }
        ClusterMember.Status status = cluster.status();
        long reachable = status.members().stream().filter(ClusterMember.MemberStatus::connected).count();
        console.print("  Makes this node the leader without a majority. Only do this when the other");
        console.print("  members are really down: two leaders at once would both start services.");
        console.print("  " + reachable + " of " + status.members().size() + " members are reachable from here.");
        if (!console.confirm("Promote this node now?", false)) {
            return;
        }
        cluster.forcePromote();
        LOGGER.info("Promoting. If another leader is still alive, the newer term makes it step down.");
    }

    @Override
    public List<String> complete(String[] args) {
        if (args.length <= 1) {
            return List.of("status", "init", "join", "add", "remove", "promote");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("remove")) {
            return config.cluster().members().stream().map(ClusterSettings.Member::name).toList();
        }
        return List.of();
    }
}
