package dev.sirius.cloud.plugin.paper;

import dev.sirius.cloud.api.driver.CloudDriver;
import dev.sirius.cloud.api.player.CloudPlayer;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.api.service.ServiceState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /cloud} in game.
 *
 * <p>Every subcommand goes through {@link CloudDriver}, so this is the same
 * API a node module or the HTTP panel uses. Nothing here reaches the node in a
 * way a third-party plugin could not, which is the point: if the command can do
 * it, so can anyone's plugin.
 *
 * <p>Results arrive asynchronously and are sent as they land. Blocking on a
 * future here would be blocking the main thread on a network round trip, which
 * is how a permissions lookup turns into a server freeze.
 */
final class CloudCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION_ROOT = "siriuscloud.command";

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String[] args) {
        if (!CloudDriver.isAvailable()) {
            send(sender, NamedTextColor.RED, "This server is not connected to a cloud.");
            return true;
        }

        if (args.length == 0) {
            usage(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (!sender.hasPermission(PERMISSION_ROOT + "." + sub)) {
            send(sender, NamedTextColor.RED, "You do not have permission for /" + label + " " + sub + ".");
            return true;
        }

        CloudDriver driver = CloudDriver.instance();

        switch (sub) {
            case "list", "services" -> list(sender, driver, args.length > 1 ? args[1] : null);
            case "info" -> {
                if (args.length < 2) {
                    send(sender, NamedTextColor.RED, "Usage: /" + label + " info <service>");
                    return true;
                }
                info(sender, driver, args[1]);
            }
            case "start" -> {
                if (args.length < 2) {
                    send(sender, NamedTextColor.RED, "Usage: /" + label + " start <group> [count]");
                    return true;
                }
                start(sender, driver, args[1], count(args, 2));
            }
            case "stop" -> {
                if (args.length < 2) {
                    send(sender, NamedTextColor.RED, "Usage: /" + label + " stop <service>");
                    return true;
                }
                stop(sender, driver, args[1]);
            }
            case "players" -> players(sender, driver);
            case "find" -> {
                if (args.length < 2) {
                    send(sender, NamedTextColor.RED, "Usage: /" + label + " find <player>");
                    return true;
                }
                find(sender, driver, args[1]);
            }
            case "send" -> {
                if (args.length < 3) {
                    send(sender, NamedTextColor.RED, "Usage: /" + label + " send <player> <service|group>");
                    return true;
                }
                sendPlayer(sender, driver, args[1], args[2]);
            }
            case "broadcast" -> {
                if (args.length < 2) {
                    send(sender, NamedTextColor.RED, "Usage: /" + label + " broadcast <message>");
                    return true;
                }
                String message = String.join(" ", List.of(args).subList(1, args.length));
                report(sender, driver.players().broadcast(message), "Broadcast sent.");
            }
            case "wrappers" -> wrappers(sender, driver);
            case "node" -> node(sender, driver);
            default -> usage(sender, label);
        }
        return true;
    }

    // ------------------------------------------------------------ subcommands

    private void list(CommandSender sender, CloudDriver driver, String group) {
        CompletableFuture<Collection<ServiceInfo>> future = group == null
                ? driver.services().services()
                : driver.services().servicesOfGroup(group);

        future.whenComplete((services, error) -> {
            if (failed(sender, error)) {
                return;
            }
            if (services.isEmpty()) {
                send(sender, NamedTextColor.GRAY, "No services are running.");
                return;
            }
            send(sender, NamedTextColor.AQUA, services.size() + " service(s):");
            services.stream()
                    .sorted(java.util.Comparator.comparing(ServiceInfo::name))
                    .forEach(service -> sender.sendMessage(Component.text("  " + service.name(),
                                    NamedTextColor.WHITE)
                            .append(Component.text("  " + service.state(), stateColour(service.state())))
                            .append(Component.text("  " + service.playerCount() + "/"
                                    + service.maxPlayers(), NamedTextColor.GRAY))));
        });
    }

    private void info(CommandSender sender, CloudDriver driver, String name) {
        Optional<ServiceInfo> cached = driver.services().cachedService(name);
        if (cached.isPresent()) {
            describe(sender, cached.get());
            return;
        }
        // Not in the local cache, so ask the node rather than claiming it does
        // not exist: the cache only holds what this server happens to have seen.
        driver.services().services().whenComplete((services, error) -> {
            if (failed(sender, error)) {
                return;
            }
            services.stream()
                    .filter(service -> service.name().equalsIgnoreCase(name))
                    .findFirst()
                    .ifPresentOrElse(service -> describe(sender, service),
                            () -> send(sender, NamedTextColor.RED, "No service named '" + name + "'."));
        });
    }

    private void describe(CommandSender sender, ServiceInfo service) {
        send(sender, NamedTextColor.AQUA, service.name());
        send(sender, NamedTextColor.GRAY, "  State   : " + service.state());
        send(sender, NamedTextColor.GRAY, "  Address : " + service.host() + ":" + service.port());
        send(sender, NamedTextColor.GRAY, "  Players : " + service.playerCount() + "/" + service.maxPlayers());
        send(sender, NamedTextColor.GRAY, "  Memory  : " + service.memory() + "MB");
        send(sender, NamedTextColor.GRAY, "  Machine : " + service.wrapperName());
    }

    private void start(CommandSender sender, CloudDriver driver, String group, int count) {
        for (int i = 0; i < count; i++) {
            driver.services().startService(group).whenComplete((service, error) -> {
                if (failed(sender, error)) {
                    return;
                }
                send(sender, NamedTextColor.GREEN, "Starting " + service.name() + ".");
            });
        }
    }

    private void stop(CommandSender sender, CloudDriver driver, String name) {
        Optional<ServiceInfo> service = driver.services().cachedService(name);
        if (service.isEmpty()) {
            send(sender, NamedTextColor.RED, "No service named '" + name + "'.");
            return;
        }
        report(sender, driver.services().stopService(service.get().uniqueId()),
                "Stopping " + service.get().name() + ".");
    }

    private void players(CommandSender sender, CloudDriver driver) {
        driver.players().onlinePlayers().whenComplete((players, error) -> {
            if (failed(sender, error)) {
                return;
            }
            if (players.isEmpty()) {
                send(sender, NamedTextColor.GRAY, "Nobody is online anywhere.");
                return;
            }
            send(sender, NamedTextColor.AQUA, players.size() + " player(s) across the cloud:");
            players.forEach(player -> send(sender, NamedTextColor.GRAY,
                    "  " + player.name() + " on " + player.serverName().orElse("(connecting)")));
        });
    }

    private void find(CommandSender sender, CloudDriver driver, String name) {
        driver.players().onlinePlayers().whenComplete((players, error) -> {
            if (failed(sender, error)) {
                return;
            }
            players.stream()
                    .filter(player -> player.name().equalsIgnoreCase(name))
                    .findFirst()
                    .ifPresentOrElse(player -> {
                        send(sender, NamedTextColor.AQUA, player.name());
                        send(sender, NamedTextColor.GRAY,
                                "  Server : " + player.serverName().orElse("(connecting)"));
                        send(sender, NamedTextColor.GRAY, "  Proxy  : " + player.proxyName());
                    }, () -> send(sender, NamedTextColor.RED, name + " is not online."));
        });
    }

    private void sendPlayer(CommandSender sender, CloudDriver driver, String name, String target) {
        Optional<CloudPlayer> player = driver.players().cachedPlayer(name);
        if (player.isEmpty()) {
            // The cache is fed by listings, so a player nobody has listed yet is
            // absent from it. One lookup fixes that.
            driver.players().onlinePlayers().whenComplete((players, error) -> {
                if (failed(sender, error)) {
                    return;
                }
                players.stream()
                        .filter(candidate -> candidate.name().equalsIgnoreCase(name))
                        .findFirst()
                        .ifPresentOrElse(found -> doSend(sender, driver, found, target),
                                () -> send(sender, NamedTextColor.RED, name + " is not online."));
            });
            return;
        }
        doSend(sender, driver, player.get(), target);
    }

    private void doSend(CommandSender sender, CloudDriver driver, CloudPlayer player, String target) {
        // A name matching a service goes there exactly; anything else is a
        // group and the node balances. Same rule as the console and the API.
        boolean isService = driver.services().cachedService(target).isPresent();
        report(sender,
                isService
                        ? driver.players().connect(player.uniqueId(), target)
                        : driver.players().connectToGroup(player.uniqueId(), target),
                "Sent " + player.name() + " to " + target + ".");
    }

    private void wrappers(CommandSender sender, CloudDriver driver) {
        driver.node().wrappers().whenComplete((wrappers, error) -> {
            if (failed(sender, error)) {
                return;
            }
            if (wrappers.isEmpty()) {
                send(sender, NamedTextColor.GRAY, "No machines are connected.");
                return;
            }
            send(sender, NamedTextColor.AQUA, wrappers.size() + " machine(s):");
            wrappers.forEach(wrapper -> send(sender, NamedTextColor.GRAY,
                    "  " + wrapper.name() + "  " + wrapper.host()
                            + "  " + wrapper.usedMemory() + "/" + wrapper.maxMemory() + "MB"));
        });
    }

    private void node(CommandSender sender, CloudDriver driver) {
        driver.node().info().whenComplete((info, error) -> {
            if (failed(sender, error)) {
                return;
            }
            send(sender, NamedTextColor.AQUA, info.name());
            send(sender, NamedTextColor.GRAY, "  Memory   : " + info.committedMemory()
                    + "/" + info.maxMemory() + "MB");
            send(sender, NamedTextColor.GRAY, "  Services : " + info.serviceCount());
            send(sender, NamedTextColor.GRAY, "  Machines : " + info.wrapperCount());
            send(sender, NamedTextColor.GRAY, "  Players  : " + info.playerCount());
        });
    }

    // ----------------------------------------------------------------- shared

    private void usage(CommandSender sender, String label) {
        send(sender, NamedTextColor.AQUA, "/" + label + " commands:");
        for (String line : new String[]{
                "list [group]           every service, or one group's",
                "info <service>         one service in detail",
                "start <group> [count]  starts services",
                "stop <service>         stops one gracefully",
                "players                everyone online, cloud-wide",
                "find <player>          which server somebody is on",
                "send <player> <target> moves them to a service or group",
                "broadcast <message>    message every player everywhere",
                "wrappers               connected machines",
                "node                   control plane status"}) {
            send(sender, NamedTextColor.GRAY, "  " + line);
        }
    }

    private void report(CommandSender sender, CompletableFuture<Void> future, String success) {
        future.whenComplete((ignored, error) -> {
            if (failed(sender, error)) {
                return;
            }
            send(sender, NamedTextColor.GREEN, success);
        });
    }

    /** Reports a failure and says whether one happened. */
    private boolean failed(CommandSender sender, Throwable error) {
        if (error == null) {
            return false;
        }
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        send(sender, NamedTextColor.RED,
                message == null ? cause.getClass().getSimpleName() : message);
        return true;
    }

    private static void send(CommandSender sender, NamedTextColor colour, String text) {
        sender.sendMessage(Component.text(text, colour));
    }

    private static NamedTextColor stateColour(ServiceState state) {
        return switch (state) {
            case RUNNING -> NamedTextColor.GREEN;
            case CRASHED -> NamedTextColor.RED;
            case STOPPED -> NamedTextColor.DARK_GRAY;
            default -> NamedTextColor.YELLOW;
        };
    }

    private static int count(String[] args, int index) {
        if (args.length <= index) {
            return 1;
        }
        try {
            return Math.clamp(Integer.parseInt(args[index]), 1, 10);
        } catch (NumberFormatException exception) {
            return 1;
        }
    }

    // ------------------------------------------------------------ completion

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, String[] args) {
        if (!CloudDriver.isAvailable()) {
            return List.of();
        }
        CloudDriver driver = CloudDriver.instance();

        if (args.length <= 1) {
            String partial = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return List.of("list", "info", "start", "stop", "players", "find",
                            "send", "broadcast", "wrappers", "node").stream()
                    .filter(name -> name.startsWith(partial))
                    .filter(name -> sender.hasPermission(PERMISSION_ROOT + "." + name))
                    .toList();
        }

        // Only cached values: completion must not make a network call on every
        // keystroke, and the cache is populated by any earlier listing.
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (sub) {
                case "info", "stop" -> cachedServiceNames(driver, args[1]);
                case "start" -> cachedGroupNames(driver, args[1]);
                default -> List.of();
            };
        }
        if (args.length == 3 && sub.equals("send")) {
            List<String> targets = new ArrayList<>(cachedServiceNames(driver, args[2]));
            targets.addAll(cachedGroupNames(driver, args[2]));
            return targets;
        }
        return List.of();
    }

    private static List<String> cachedServiceNames(CloudDriver driver, String partial) {
        String prefix = partial.toLowerCase(Locale.ROOT);
        return driver.services().services().getNow(List.of()).stream()
                .map(ServiceInfo::name)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
    }

    private static List<String> cachedGroupNames(CloudDriver driver, String partial) {
        String prefix = partial.toLowerCase(Locale.ROOT);
        return driver.groups().groups().getNow(List.of()).stream()
                .map(dev.sirius.cloud.api.group.ServiceGroup::name)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
    }
}
