package dev.sirius.cloud.plugin.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code /server} on the proxy.
 *
 * <p>Velocity ships one, but only for servers listed in {@code velocity.toml},
 * and this cloud deliberately lists none. Without this the only way to move
 * between servers in game was {@code /hub}, which goes one direction.
 *
 * <p>A group name works as well as a server name: typing {@code /server Lobby}
 * should land you on a lobby rather than fail because the server is really
 * called {@code Lobby-2}.
 */
final class ServerCommand implements SimpleCommand {

    private static final String PERMISSION = "siriuscloud.server";

    private final ProxyServer proxy;

    ServerCommand(ProxyServer proxy) {
        this.proxy = proxy;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!(source instanceof Player player)) {
            source.sendMessage(Component.text("Only players can use /server.", NamedTextColor.RED));
            return;
        }
        if (!source.hasPermission(PERMISSION)) {
            source.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
            return;
        }

        String[] args = invocation.arguments();
        if (args.length == 0) {
            list(player);
            return;
        }

        Optional<RegisteredServer> target = resolve(args[0]);
        if (target.isEmpty()) {
            player.sendMessage(Component.text("No server or group called '" + args[0] + "'.",
                    NamedTextColor.RED));
            list(player);
            return;
        }

        RegisteredServer server = target.get();
        boolean alreadyThere = player.getCurrentServer()
                .map(current -> current.getServerInfo().getName().equals(server.getServerInfo().getName()))
                .orElse(false);
        if (alreadyThere) {
            player.sendMessage(Component.text("You are already on "
                    + server.getServerInfo().getName() + ".", NamedTextColor.YELLOW));
            return;
        }

        player.sendMessage(Component.text("Sending you to " + server.getServerInfo().getName() + "...",
                NamedTextColor.GRAY));
        player.createConnectionRequest(server).fireAndForget();
    }

    /** An exact server name, or the least loaded member of a group. */
    private Optional<RegisteredServer> resolve(String name) {
        Optional<RegisteredServer> exact = proxy.getServer(name);
        if (exact.isPresent()) {
            return exact;
        }
        String prefix = name.toLowerCase(Locale.ROOT) + "-";
        return proxy.getAllServers().stream()
                .filter(server -> server.getServerInfo().getName()
                        .toLowerCase(Locale.ROOT).startsWith(prefix))
                .min(Comparator.comparingInt(server -> server.getPlayersConnected().size()));
    }

    private void list(Player player) {
        List<RegisteredServer> servers = proxy.getAllServers().stream()
                .sorted(Comparator.comparing(server -> server.getServerInfo().getName()))
                .toList();

        if (servers.isEmpty()) {
            player.sendMessage(Component.text("No servers are available right now.",
                    NamedTextColor.RED));
            return;
        }

        player.sendMessage(Component.text("Servers:", NamedTextColor.AQUA));
        servers.forEach(server -> player.sendMessage(Component.text(
                "  " + server.getServerInfo().getName()
                        + " (" + server.getPlayersConnected().size() + ")",
                NamedTextColor.GRAY)));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        String partial = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        return proxy.getAllServers().stream()
                .map(server -> server.getServerInfo().getName())
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(partial))
                .toList();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(PERMISSION);
    }
}
