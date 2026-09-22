package dev.sirius.cloud.node.setup;

import dev.sirius.cloud.api.group.ServiceGroup;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.service.ServiceType;
import dev.sirius.cloud.node.config.NodeConfig;
import dev.sirius.cloud.node.console.NodeConsole;
import dev.sirius.cloud.node.group.GroupRegistry;

import java.io.IOException;

/**
 * Offers to create a first group when the node starts with none.
 *
 * <p>Replaces silently writing a default {@code Lobby} group on first boot.
 * That was convenient but presumptuous: it invented configuration the operator
 * never asked for, and immediately started a server from it, which is a
 * surprising thing for a first run to do on its own.
 *
 * <p>Re-runnable through the {@code setup} command, so declining is not a dead
 * end for anyone who has not yet learned the group file format.
 */
public final class FirstRunSetup {

    private static final CloudLogger LOGGER = CloudLogger.of("Setup");

    private final NodeConsole console;
    private final GroupRegistry groups;
    private final NodeConfig config;

    public FirstRunSetup(NodeConsole console, GroupRegistry groups, NodeConfig config) {
        this.console = console;
        this.groups = groups;
        this.config = config;
    }

    /**
     * Runs the flow.
     *
     * @param firstRun true when triggered automatically on an unconfigured node
     * @return the group that was created, or null if none was
     */
    public ServiceGroup run(boolean firstRun) throws IOException {
        // Nothing may block on an answer that cannot arrive: under systemd, in
        // a container without a TTY, or with piped stdin, a prompt would hang
        // the node forever. Fall back to the old implicit default there.
        if (!console.isInteractive()) {
            if (firstRun) {
                LOGGER.info("No groups configured and no terminal to ask on; creating a default 'Lobby'.");
                return groups.create(new ServiceGroup("Lobby", ServiceType.SERVER));
            }
            LOGGER.warn("'setup' needs an interactive terminal.");
            return null;
        }

        console.heading(firstRun ? "First-time setup" : "Create a group");

        if (firstRun) {
            console.print("  This node has no groups yet. A group is a template that");
            console.print("  servers are started from - a 'Lobby' group gives you lobby servers.");
            console.print("");
        }

        if (!console.confirm("Create a Lobby group now?", true)) {
            console.print("");
            LOGGER.info("No group created. Run 'setup' whenever you want one.");
            return null;
        }

        String name = console.ask("Group name", "Lobby");
        int memory = console.askInt("Memory per server in MB", 1024, 256, 65536);
        int online = console.askInt("Servers to keep online", 1, 0, 100);
        int maxPlayers = console.askInt("Max players per server", 50, 1, 10000);
        String version = console.ask("Minecraft version ('latest' = newest stable)", "latest");

        ServiceGroup group = new ServiceGroup(name, ServiceType.SERVER);
        group.memory(memory);
        group.minServiceCount(online);
        // Headroom to scale up by hand without editing the file straight away.
        group.maxServiceCount(Math.max(online + 4, 5));
        group.maxPlayers(maxPlayers);
        group.version(version);

        groups.create(group);

        console.print("");
        LOGGER.info("Created group '{}' ({}MB, keeping {} online, Paper {})",
                name, memory, online, version);

        // A group that commits more memory than the node allows would be
        // accepted here and then refuse to start, which is a confusing way to
        // find out. Say it now, while the numbers are still on screen.
        int committed = online * memory;
        if (committed > config.maxMemory()) {
            LOGGER.warn("Keeping {} online needs {}MB but the node allows {}MB.",
                    online, committed, config.maxMemory());
            LOGGER.warn("Raise 'maxMemory' in node/config.json or lower the count.");
        }

        if (online > 0) {
            LOGGER.info("{}-1 will start as soon as a wrapper connects.", name);
        }

        LOGGER.info("Edit node/groups/{}.json to change any of this.", name);
        console.print("");
        return group;
    }
}
