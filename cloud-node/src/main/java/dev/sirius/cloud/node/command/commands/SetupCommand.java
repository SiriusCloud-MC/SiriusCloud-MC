package dev.sirius.cloud.node.command.commands;

import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.node.command.Command;
import dev.sirius.cloud.node.setup.FirstRunSetup;

import java.io.IOException;

/** Re-runs the interactive group setup. */
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
    public String description() {
        return "Creates a group interactively";
    }

    @Override
    public void execute(String[] args) {
        try {
            setup.run(false);
        } catch (IOException exception) {
            LOGGER.error("Could not save the group", exception);
        }
    }
}
