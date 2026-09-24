package dev.sirius.cloud.node.command.commands;

import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.node.command.Command;
import dev.sirius.cloud.node.group.GroupRegistry;

import java.io.IOException;

/** Re-reads the group files without restarting the node. */
public final class ReloadCommand implements Command {

    private static final CloudLogger LOGGER = CloudLogger.of("Console");

    private final GroupRegistry groups;

    public ReloadCommand(GroupRegistry groups) {
        this.groups = groups;
    }

    @Override
    public String name() {
        return "reload";
    }

    @Override
    public String description() {
        return "Re-reads node/groups/ from disk";
    }

    @Override
    public void execute(String[] args) {
        try {
            int count = groups.reload();
            LOGGER.info("Reloaded {} group(s) from disk.", count);
            // Worth saying every time: somebody editing a group file is usually
            // trying to change a server that is already running, and it will
            // not change until that service is restarted.
            LOGGER.info("Running services keep their current settings until restarted.");
        } catch (IOException exception) {
            LOGGER.error("Could not reload the groups", exception);
        }
    }
}
