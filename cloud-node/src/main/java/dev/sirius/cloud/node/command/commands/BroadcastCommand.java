package dev.sirius.cloud.node.command.commands;

import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.node.command.Command;
import dev.sirius.cloud.node.player.PlayerManager;

import java.util.List;

public final class BroadcastCommand implements Command {

    private static final CloudLogger LOGGER = CloudLogger.of("Players");

    private final PlayerManager playerManager;

    public BroadcastCommand(PlayerManager playerManager) {
        this.playerManager = playerManager;
    }

    @Override
    public String name() {
        return "broadcast";
    }

    @Override
    public List<String> aliases() {
        return List.of("bc", "say");
    }

    @Override
    public String usage() {
        return "broadcast <message>";
    }

    @Override
    public String description() {
        return "Messages every player on every proxy";
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 0) {
            LOGGER.warn("Usage: {}", usage());
            return;
        }
        playerManager.broadcast(String.join(" ", args));
    }
}
