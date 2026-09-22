package dev.sirius.cloud.node.command.commands;

import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.node.command.Command;
import dev.sirius.cloud.node.setup.FirstRunSetup;

import java.io.IOException;
import java.util.List;

/** Re-runs the interactive configuration. */
public final class SetupCommand implements Command {

    private static final CloudLogger LOGGER = CloudLogger.of("Setup");

    private final FirstRunSetup setup;

    public SetupCommand(FirstRunSetup setup) {
        this.setup = setup;
    }

    @Override
    public String name() {
        return "setup";
    }

    @Override
    public String usage() {
        return "setup [group|node]";
    }

    @Override
    public String description() {
        return "Configures a group, or the node itself, by question";
    }

    @Override
    public void execute(String[] args) {
        String target = args.length > 0 ? args[0].toLowerCase(java.util.Locale.ROOT) : "group";

        try {
            switch (target) {
                case "node" -> setup.configureNode(false);
                case "group" -> setup.createGroup(false);
                default -> LOGGER.warn("Usage: {}", usage());
            }
        } catch (IOException exception) {
            LOGGER.error("Could not save the configuration", exception);
        }
    }

    @Override
    public List<String> complete(String[] args) {
        return args.length <= 1 ? List.of("group", "node") : List.of();
    }
}
