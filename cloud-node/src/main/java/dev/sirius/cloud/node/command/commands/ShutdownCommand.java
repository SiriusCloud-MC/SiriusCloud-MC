package dev.sirius.cloud.node.command.commands;

import dev.sirius.cloud.node.CloudNode;
import dev.sirius.cloud.node.command.Command;

import java.util.List;

public final class ShutdownCommand implements Command {

    private final CloudNode node;

    public ShutdownCommand(CloudNode node) {
        this.node = node;
    }

    @Override
    public String name() {
        return "shutdown";
    }

    @Override
    public List<String> aliases() {
        return List.of("exit", "quit");
    }

    @Override
    public String description() {
        return "Stops every service, then the node itself";
    }

    @Override
    public void execute(String[] args) {
        node.shutdown();
    }
}
