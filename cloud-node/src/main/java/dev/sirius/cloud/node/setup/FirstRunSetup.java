package dev.sirius.cloud.node.setup;

import dev.sirius.cloud.api.group.ServiceGroup;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.platform.SystemMemory;
import dev.sirius.cloud.api.service.ServiceType;
import dev.sirius.cloud.driver.config.JsonConfig;
import dev.sirius.cloud.node.config.NodeConfig;
import dev.sirius.cloud.node.console.NodeConsole;
import dev.sirius.cloud.node.group.GroupRegistry;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Interactive configuration for the node.
 *
 * <p>Replaces silently writing a default config and a default {@code Lobby}
 * group on first boot. That was convenient but presumptuous: it invented
 * configuration nobody asked for, and picked a memory budget with no idea how
 * much the machine actually has.
 *
 * <p>Every question carries a default — several derived from the machine
 * itself — so pressing Enter through the whole thing still takes seconds. Both
 * halves are re-runnable through the {@code setup} command.
 */
public final class FirstRunSetup {

    private static final CloudLogger LOGGER = CloudLogger.of("Setup");

    private final NodeConsole console;
    private final GroupRegistry groups;
    private final NodeConfig config;
    private final Path configFile;

    public FirstRunSetup(NodeConsole console, GroupRegistry groups, NodeConfig config, Path configFile) {
        this.console = console;
        this.groups = groups;
        this.config = config;
        this.configFile = configFile;
    }

    /** The whole first-run flow: node settings, then a first group. */
    public void runFirstRun() throws IOException {
        if (!console.isInteractive()) {
            LOGGER.info("No terminal to ask on; using defaults.");
            LOGGER.info("Memory budget: {}MB. Edit node/config.json to change it.", config.maxMemory());
            if (groups.isEmpty()) {
                LOGGER.info("Creating a default 'Lobby' group.");
                groups.create(new ServiceGroup("Lobby", ServiceType.SERVER));
            }
            return;
        }

        console.heading("First-time setup");
        console.print("  Nothing here is permanent - everything lands in node/config.json");
        console.print("  and node/groups/, and Enter accepts the suggested value.");

        configureNode(true);

        if (groups.isEmpty()) {
            createGroup(true);
        }
    }

    // ------------------------------------------------------------------ node

    /**
     * Node-level settings.
     *
     * @param firstRun true when part of the automatic first-run flow
     */
    public void configureNode(boolean firstRun) throws IOException {
        if (!console.isInteractive()) {
            LOGGER.warn("'setup node' needs an interactive terminal.");
            return;
        }

        console.heading("Node");

        config.nodeName(console.ask("Name for this node", config.nodeName()));

        // Memory is the question people most often get wrong, so it is asked
        // against the machine's real capacity rather than a blind default.
        console.print("");
        console.print("  This machine has " + SystemMemory.describeTotal() + " of RAM.");
        console.print("  The node will refuse to start services beyond this budget.");
        int suggested = firstRun
                ? SystemMemory.suggestedBudgetMegabytes(config.maxMemory())
                : config.maxMemory();
        config.maxMemory(console.askInt(
                "How much RAM may this node use for services, in MB", suggested, 256, 1024 * 1024));

        console.print("");
        config.port(console.askInt("Port wrappers and services connect to", config.port(), 1, 65535));
        config.bindAddress(console.ask(
                "Address to listen on (0.0.0.0 accepts remote wrappers)", config.bindAddress()));
        config.connectAddress(console.ask(
                "Address wrappers should dial back (this machine's IP if remote)", config.connectAddress()));

        console.print("");
        config.minimumPaperVersion(console.ask(
                "Oldest Paper version to list in 'versions'", config.minimumPaperVersion()));

        JsonConfig.save(configFile, config);

        console.print("");
        LOGGER.info("Node '{}' listening on {}:{}, {}MB budget",
                config.nodeName(), config.bindAddress(), config.port(), config.maxMemory());

        if (!firstRun) {
            LOGGER.warn("Address and port changes take effect when the node restarts.");
        }
    }

    // ----------------------------------------------------------------- group

    /**
     * Creates one group.
     *
     * @param firstRun true when part of the automatic first-run flow
     * @return the group created, or null if the operator declined
     */
    public ServiceGroup createGroup(boolean firstRun) throws IOException {
        if (!console.isInteractive()) {
            LOGGER.warn("'setup' needs an interactive terminal.");
            return null;
        }

        console.heading(firstRun ? "First group" : "New group");
        console.print("  A group is a template servers are started from -");
        console.print("  a 'Lobby' group gives you lobby servers.");
        console.print("");

        if (firstRun && !console.confirm("Create a Lobby group now?", true)) {
            console.print("");
            LOGGER.info("No group created. Run 'setup' whenever you want one.");
            return null;
        }

        String name = console.ask("Group name", firstRun ? "Lobby" : "");
        if (name.isBlank()) {
            LOGGER.warn("A group needs a name.");
            return null;
        }
        if (groups.byName(name).isPresent()) {
            LOGGER.warn("A group called '{}' already exists.", name);
            return null;
        }

        console.print("");
        int memory = console.askInt("Maximum RAM per server, in MB", 1024, 256, 1024 * 1024);
        int minMemory = console.askInt(
                "Starting RAM per server, in MB (same as maximum is usual)", memory, 256, memory);

        console.print("");
        int online = console.askInt("Servers to keep online at all times", 1, 0, 100);
        int maximum = console.askInt("Maximum servers of this group", Math.max(online + 4, 5), online, 100);
        int maxPlayers = console.askInt("Max players per server", 50, 1, 10000);

        console.print("");
        String version = console.ask("Minecraft version ('latest' = newest stable)", "latest");
        int startPort = console.askInt("First port for these servers", nextFreePortRange(), 1024, 65000);
        boolean staticService = console.confirm(
                "Keep each server's files between restarts (static)?", false);

        ServiceGroup group = new ServiceGroup(name, ServiceType.SERVER);
        group.memory(memory);
        group.minMemory(minMemory);
        group.minServiceCount(online);
        group.maxServiceCount(maximum);
        group.maxPlayers(maxPlayers);
        group.version(version);
        group.startPort(startPort);
        group.staticService(staticService);

        groups.create(group);

        console.print("");
        LOGGER.info("Created '{}': {}-{} servers, {}-{}MB each, port {}+, Paper {}{}",
                name, online, maximum, minMemory, memory, startPort, version,
                staticService ? ", static" : "");

        // Accepting a budget the node cannot honour would be discovered later
        // as a group that refuses to start. Say it while the numbers are up.
        int committed = online * memory;
        if (committed > config.maxMemory()) {
            LOGGER.warn("Keeping {} online needs {}MB but this node allows {}MB.",
                    online, committed, config.maxMemory());
            LOGGER.warn("Run 'setup node' to raise the budget, or lower the count.");
        }

        if (online > 0) {
            LOGGER.info("{}-1 will start as soon as a wrapper connects.", name);
        }
        LOGGER.info("Edit node/groups/{}.json to change any of this.", name);
        console.print("");
        return group;
    }

    /** Suggests a port range that does not collide with an existing group. */
    private int nextFreePortRange() {
        int highest = 41000;
        for (ServiceGroup group : groups.all()) {
            // Leave room for the group's whole range before the next one.
            highest = Math.max(highest, group.startPort() + group.maxServiceCount() + 10);
        }
        return highest;
    }
}
