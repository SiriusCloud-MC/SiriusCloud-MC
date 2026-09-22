package dev.sirius.cloud.node.command.commands;

import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.player.CloudPlayer;
import dev.sirius.cloud.node.command.Command;
import dev.sirius.cloud.node.player.PlayerRegistry;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

public final class PlayersCommand implements Command {

    private final PlayerRegistry players;

    public PlayersCommand(PlayerRegistry players) {
        this.players = players;
    }

    @Override
    public String name() {
        return "players";
    }

    @Override
    public List<String> aliases() {
        return List.of("online");
    }

    @Override
    public String usage() {
        return "players [service]";
    }

    @Override
    public String description() {
        return "Lists players online across the cloud";
    }

    @Override
    public void execute(String[] args) {
        Collection<CloudPlayer> online = args.length > 0
                ? players.onService(args[0])
                : players.all();

        if (online.isEmpty()) {
            CloudLogger.raw(args.length > 0
                    ? "Nobody is on " + args[0] + "."
                    : "Nobody is online.");
            return;
        }

        CloudLogger.raw(String.format("%-18s %-18s %-14s %-10s %s",
                "NAME", "SERVER", "PROXY", "ONLINE", "ADDRESS"));

        online.stream()
                .sorted(Comparator.comparing(CloudPlayer::name, String.CASE_INSENSITIVE_ORDER))
                .forEach(player -> CloudLogger.raw(String.format("%-18s %-18s %-14s %-10s %s",
                        player.name(),
                        player.serverName().orElse("-"),
                        player.proxyName(),
                        ServicesCommand.formatUptime(player.onlineMillis()),
                        player.address())));

        CloudLogger.raw(online.size() + " player(s).");
    }
}
