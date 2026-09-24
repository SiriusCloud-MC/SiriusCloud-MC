package dev.sirius.cloud.plugin.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import dev.sirius.cloud.api.driver.CloudDriver;
import dev.sirius.cloud.api.group.ServiceGroup;
import dev.sirius.cloud.api.player.CloudPlayer;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.api.service.ServiceState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /cloud} on the proxy.
 *
 * <p>The same subcommands as the backend version and, deliberately, the same
 * route to the cloud: everything goes through {@link CloudDriver}. A proxy has
 * no privileged access here, which is what keeps the API the only way in.
 */
final class CloudCommand implements SimpleCommand {

    private static final String PERMISSION_ROOT = "siriuscloud.command";

    private static final List<String> SUBCOMMANDS = List.of(
            "list", "info", "start", "stop", "players", "find",
            "send", "broadcast", "wrappers", "node");

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (!CloudDriver.isAvailable()) {
            send(source, NamedTextColor.RED, "This proxy is not connected to a cloud.");
            return;
        }
        if (args.length == 0) {
            usage(source);
            return;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (!source.hasPermission(PERMISSION_ROOT + "." + sub)) {
            send(source, NamedTextColor.RED, "You do not have permission for /cloud " + sub + ".");
            return;
        }

        CloudDriver driver = CloudDriver.instance();

        switch (sub) {
            case "list", "services" -> list(source, driver, args.length > 1 ? args[1] : null);
            case "info" -> {
                if (args.length < 2) {
                    send(source, NamedTextColor.RED, "Usage: /cloud info <service>");
                    return;
                }
                info(source, driver, args[1]);
            }
            case "start" -> {
                if (args.length < 2) {
                    send(source, NamedTextColor.RED, "Usage: /cloud start <group> [count]");
                    return;
                }
                int count = count(args, 2);
                for (int i = 0; i < count; i++) {
                    driver.services().startService(args[1]).whenComplete((service, error) -> {
                        if (!failed(source, error)) {
                            send(source, NamedTextColor.GREEN, "Starting " + service.name() + ".");
                        }
                    });
                }
            }
            case "stop" -> {
                if (args.length < 2) {
                    send(source, NamedTextColor.RED, "Usage: /cloud stop <service>");
                    return;
                }
                Optional<ServiceInfo> service = driver.services().cachedService(args[1]);
                if (service.isEmpty()) {
                    send(source, NamedTextColor.RED, "No service named '" + args[1] + "'.");
                    return;
                }
                report(source, driver.services().stopService(service.get().uniqueId()),
                        "Stopping " + service.get().name() + ".");
            }
            case "players" -> driver.players().onlinePlayers().whenComplete((players, error) -> {
                if (failed(source, error)) {
                    return;
                }
                if (players.isEmpty()) {
                    send(source, NamedTextColor.GRAY, "Nobody is online anywhere.");
                    return;
                }
                send(source, NamedTextColor.AQUA, players.size() + " player(s) across the cloud:");
                players.forEach(player -> send(source, NamedTextColor.GRAY,
                        "  " + player.name() + " on " + player.serverName().orElse("(connecting)")));
            });
            case "find" -> {
                if (args.length < 2) {
                    send(source, NamedTextColor.RED, "Usage: /cloud find <player>");
                    return;
                }
                find(source, driver, args[1]);
            }
            case "send" -> {
                if (args.length < 3) {
                    send(source, NamedTextColor.RED, "Usage: /cloud send <player> <service|group>");
                    return;
                }
                sendPlayer(source, driver, args[1], args[2]);
            }
            case "broadcast" -> {
                if (args.length < 2) {
                    send(source, NamedTextColor.RED, "Usage: /cloud broadcast <message>");
                    return;
                }
                report(source,
                        driver.players().broadcast(String.join(" ", List.of(args).subList(1, args.length))),
                        "Broadcast sent.");
            }
            case "wrappers" -> driver.node().wrappers().whenComplete((wrappers, error) -> {
                if (failed(source, error)) {
                    return;
                }
                if (wrappers.isEmpty()) {
                    send(source, NamedTextColor.GRAY, "No machines are connected.");
                    return;
                }
                send(source, NamedTextColor.AQUA, wrappers.size() + " machine(s):");
                wrappers.forEach(wrapper -> send(source, NamedTextColor.GRAY,
                        "  " + wrapper.name() + "  " + wrapper.host()
                                + "  " + wrapper.usedMemory() + "/" + wrapper.maxMemory() + "MB"));
            });
            case "node" -> driver.node().info().whenComplete((info, error) -> {
                if (failed(source, error)) {
                    return;
                }
                send(source, NamedTextColor.AQUA, info.name());
                send(source, NamedTextColor.GRAY, "  Memory   : " + info.committedMemory()
                        + "/" + info.maxMemory() + "MB");
                send(source, NamedTextColor.GRAY, "  Services : " + info.serviceCount());
                send(source, NamedTextColor.GRAY, "  Machines : " + info.wrapperCount());
                send(source, NamedTextColor.GRAY, "  Players  : " + info.playerCount());
            });
            default -> usage(source);
        }
    }

    private void list(CommandSource source, CloudDriver driver, String group) {
        CompletableFuture<Collection<ServiceInfo>> future = group == null
                ? driver.services().services()
                : driver.services().servicesOfGroup(group);

        future.whenComplete((services, error) -> {
            if (failed(source, error)) {
                return;
            }
            if (services.isEmpty()) {
                send(source, NamedTextColor.GRAY, "No services are running.");
                return;
            }
            send(source, NamedTextColor.AQUA, services.size() + " service(s):");
            services.stream().sorted(Comparator.comparing(ServiceInfo::name))
                    .forEach(service -> source.sendMessage(
                            Component.text("  " + service.name(), NamedTextColor.WHITE)
                                    .append(Component.text("  " + service.state(),
                                            stateColour(service.state())))
                                    .append(Component.text("  " + service.playerCount() + "/"
                                            + service.maxPlayers(), NamedTextColor.GRAY))));
        });
    }

    private void info(CommandSource source, CloudDriver driver, String name) {
        driver.services().services().whenComplete((services, error) -> {
            if (failed(source, error)) {
                return;
            }
            services.stream()
                    .filter(service -> service.name().equalsIgnoreCase(name))
                    .findFirst()
                    .ifPresentOrElse(service -> {
                        send(source, NamedTextColor.AQUA, service.name());
                        send(source, NamedTextColor.GRAY, "  State   : " + service.state());
                        send(source, NamedTextColor.GRAY, "  Address : "
                                + service.host() + ":" + service.port());
                        send(source, NamedTextColor.GRAY, "  Players : "
                                + service.playerCount() + "/" + service.maxPlayers());
                        send(source, NamedTextColor.GRAY, "  Machine : " + service.wrapperName());
                    }, () -> send(source, NamedTextColor.RED, "No service named '" + name + "'."));
        });
    }

    private void find(CommandSource source, CloudDriver driver, String name) {
        driver.players().onlinePlayers().whenComplete((players, error) -> {
            if (failed(source, error)) {
                return;
            }
            players.stream().filter(player -> player.name().equalsIgnoreCase(name)).findFirst()
                    .ifPresentOrElse(player -> {
                        send(source, NamedTextColor.AQUA, player.name());
                        send(source, NamedTextColor.GRAY, "  Server : "
                                + player.serverName().orElse("(connecting)"));
                        send(source, NamedTextColor.GRAY, "  Proxy  : " + player.proxyName());
                    }, () -> send(source, NamedTextColor.RED, name + " is not online."));
        });
    }

    private void sendPlayer(CommandSource source, CloudDriver driver, String name, String target) {
        driver.players().onlinePlayers().whenComplete((players, error) -> {
            if (failed(source, error)) {
                return;
            }
            Optional<CloudPlayer> player = players.stream()
                    .filter(candidate -> candidate.name().equalsIgnoreCase(name))
                    .findFirst();
            if (player.isEmpty()) {
                send(source, NamedTextColor.RED, name + " is not online.");
                return;
            }
            boolean isService = driver.services().cachedService(target).isPresent();
            report(source,
                    isService
                            ? driver.players().connect(player.get().uniqueId(), target)
                            : driver.players().connectToGroup(player.get().uniqueId(), target),
                    "Sent " + player.get().name() + " to " + target + ".");
        });
    }

    private void usage(CommandSource source) {
        send(source, NamedTextColor.AQUA, "/cloud commands:");
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
            send(source, NamedTextColor.GRAY, "  " + line);
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (!CloudDriver.isAvailable()) {
            return List.of();
        }
        CloudDriver driver = CloudDriver.instance();
        String[] args = invocation.arguments();

        if (args.length <= 1) {
            String partial = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream()
                    .filter(name -> name.startsWith(partial))
                    .filter(name -> invocation.source().hasPermission(PERMISSION_ROOT + "." + name))
                    .toList();
        }

        // Cached only: suggestion must not make a network call per keystroke.
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (sub) {
                case "info", "stop" -> services(driver, args[1]);
                case "start" -> groups(driver, args[1]);
                default -> List.of();
            };
        }
        if (args.length == 3 && sub.equals("send")) {
            List<String> targets = new ArrayList<>(services(driver, args[2]));
            targets.addAll(groups(driver, args[2]));
            return targets;
        }
        return List.of();
    }

    private static List<String> services(CloudDriver driver, String partial) {
        String prefix = partial.toLowerCase(Locale.ROOT);
        return driver.services().services().getNow(List.of()).stream()
                .map(ServiceInfo::name)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
    }

    private static List<String> groups(CloudDriver driver, String partial) {
        String prefix = partial.toLowerCase(Locale.ROOT);
        return driver.groups().groups().getNow(List.of()).stream()
                .map(ServiceGroup::name)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
    }

    private void report(CommandSource source, CompletableFuture<Void> future, String success) {
        future.whenComplete((ignored, error) -> {
            if (!failed(source, error)) {
                send(source, NamedTextColor.GREEN, success);
            }
        });
    }

    private boolean failed(CommandSource source, Throwable error) {
        if (error == null) {
            return false;
        }
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        send(source, NamedTextColor.RED,
                cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
        return true;
    }

    private static void send(CommandSource source, NamedTextColor colour, String text) {
        source.sendMessage(Component.text(text, colour));
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
}
