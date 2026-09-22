package dev.sirius.cloud.node.command.commands;

import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.player.CloudPlayer;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.node.command.Command;
import dev.sirius.cloud.node.group.GroupRegistry;
import dev.sirius.cloud.node.player.PlayerManager;
import dev.sirius.cloud.node.player.PlayerRegistry;
import dev.sirius.cloud.node.service.ServiceRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Acts on one player, wherever in the cloud they happen to be. */
public final class PlayerCommand implements Command {

    private static final CloudLogger LOGGER = CloudLogger.of("Players");

    private final PlayerRegistry players;
    private final PlayerManager playerManager;
    private final ServiceRegistry services;
    private final GroupRegistry groups;

    public PlayerCommand(PlayerRegistry players,
                         PlayerManager playerManager,
                         ServiceRegistry services,
                         GroupRegistry groups) {
        this.players = players;
        this.playerManager = playerManager;
        this.services = services;
        this.groups = groups;
    }

    @Override
    public String name() {
        return "player";
    }

    @Override
    public String usage() {
        return "player <name> [send <target>|msg <text>|kick [reason]]";
    }

    @Override
    public String description() {
        return "Shows or acts on one player";
    }

    @Override
    public void execute(String[] args) {
        if (args.length < 1) {
            LOGGER.warn("Usage: {}", usage());
            return;
        }

        Optional<CloudPlayer> found = players.byName(args[0]);
        if (found.isEmpty()) {
            LOGGER.warn("{} is not online.", args[0]);
            return;
        }
        CloudPlayer player = found.get();

        if (args.length == 1) {
            info(player);
            return;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        String rest = args.length > 2
                ? String.join(" ", List.of(args).subList(2, args.length))
                : "";

        switch (action) {
            case "send", "connect" -> {
                if (rest.isBlank()) {
                    LOGGER.warn("Usage: player {} send <service|group>", player.name());
                    return;
                }
                // A name that matches a service goes there exactly; otherwise
                // it is treated as a group and the node balances.
                boolean isService = services.byName(rest).isPresent();
                (isService
                        ? playerManager.connect(player.uniqueId(), rest)
                        : playerManager.connectToGroup(player.uniqueId(), rest))
                        .whenComplete((ignored, error) -> {
                            if (error != null) {
                                LOGGER.warn("Could not send {}: {}", player.name(), rootMessage(error));
                            } else {
                                LOGGER.info("Sending {} to {}", player.name(), rest);
                            }
                        });
            }
            case "msg", "message", "tell" -> {
                if (rest.isBlank()) {
                    LOGGER.warn("Usage: player {} msg <text>", player.name());
                    return;
                }
                playerManager.sendMessage(player.uniqueId(), rest)
                        .whenComplete((ignored, error) -> {
                            if (error != null) {
                                LOGGER.warn("Could not message {}: {}", player.name(), rootMessage(error));
                            } else {
                                LOGGER.info("-> {}: {}", player.name(), rest);
                            }
                        });
            }
            case "kick" -> {
                String reason = rest.isBlank() ? "Disconnected by an administrator" : rest;
                playerManager.kick(player.uniqueId(), reason)
                        .whenComplete((ignored, error) -> {
                            if (error != null) {
                                LOGGER.warn("Could not kick {}: {}", player.name(), rootMessage(error));
                            } else {
                                LOGGER.info("Kicked {}: {}", player.name(), reason);
                            }
                        });
            }
            default -> LOGGER.warn("Usage: {}", usage());
        }
    }

    private void info(CloudPlayer player) {
        CloudLogger.raw("Player   : " + player.name());
        CloudLogger.raw("UUID     : " + player.uniqueId());
        CloudLogger.raw("Server   : " + player.serverName().orElse("(connecting)"));
        CloudLogger.raw("Proxy    : " + player.proxyName());
        CloudLogger.raw("Address  : " + player.address());
        CloudLogger.raw("Online   : " + ServicesCommand.formatUptime(player.onlineMillis()));
    }

    @Override
    public List<String> complete(String[] args) {
        if (args.length <= 1) {
            return players.all().stream().map(CloudPlayer::name).toList();
        }
        if (args.length == 2) {
            return List.of("send", "msg", "kick");
        }
        if (args.length == 3 && (args[1].equalsIgnoreCase("send") || args[1].equalsIgnoreCase("connect"))) {
            List<String> targets = new ArrayList<>(services.all().stream().map(ServiceInfo::name).toList());
            groups.all().forEach(group -> targets.add(group.name()));
            return targets;
        }
        return List.of();
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
