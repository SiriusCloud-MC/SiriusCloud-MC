package dev.sirius.cloud.plugin.velocity;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import dev.sirius.cloud.api.network.ProxyDisplay;
import dev.sirius.cloud.protocol.connection.NetworkChannel;
import dev.sirius.cloud.protocol.connection.NetworkClient;
import dev.sirius.cloud.protocol.packet.impl.LoginCheckPacket;
import dev.sirius.cloud.protocol.packet.impl.LoginVerdictPacket;
import dev.sirius.cloud.protocol.packet.impl.NetworkCommandPacket;
import dev.sirius.cloud.protocol.packet.impl.NetworkCommandsPacket;
import dev.sirius.cloud.protocol.packet.impl.NetworkSuggestionsPacket;
import dev.sirius.cloud.protocol.packet.impl.PlayerMessagePacket;
import dev.sirius.cloud.protocol.packet.impl.ProxyDisplayPacket;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The generic half of the network API on the proxy.
 *
 * <p>Nothing in here knows what a party or a ban is. It registers whatever
 * commands the node lists, forwards each use to the node, asks the node about
 * every login, and renders the display it is sent. That is what lets a feature
 * be one node module rather than a module plus a proxy plugin to keep in step
 * with it.
 */
final class NetworkBridge {

    /** How long a login waits for the node before letting the player in. */
    private static final long LOGIN_TIMEOUT_MILLIS = 3_000;

    /** Past this, tab completion is simply empty rather than laggy. */
    private static final long SUGGEST_TIMEOUT_MILLIS = 1_500;

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final ProxyServer proxy;
    private final Object plugin;
    private final Logger logger;
    private final Supplier<NetworkClient> client;
    private final Supplier<String> proxyName;
    private final IntSupplier capacity;
    private final IntSupplier servers;

    /** Network commands currently registered, by lowercased name, so a resync can remove stale ones. */
    private final Map<String, CommandMeta> registered = new ConcurrentHashMap<>();
    private final Map<String, NetworkCommandsPacket.Definition> definitions = new ConcurrentHashMap<>();

    private volatile List<String> loginPermissions = List.of();
    private volatile ProxyDisplay display;
    private volatile int networkOnline = -1;

    NetworkBridge(ProxyServer proxy, Object plugin, Logger logger, Supplier<NetworkClient> client,
                  Supplier<String> proxyName, IntSupplier capacity, IntSupplier servers) {
        this.proxy = proxy;
        this.plugin = plugin;
        this.logger = logger;
        this.client = client;
        this.proxyName = proxyName;
        this.capacity = capacity;
        this.servers = servers;
    }

    // --------------------------------------------------------------- commands

    /**
     * Replaces the registered network commands with the node's list.
     *
     * <p>A name that collides with a command the proxy already has - {@code /hub},
     * {@code /server}, another plugin's - is skipped with a warning rather than
     * taken over, because silently replacing a command that was working is the
     * worse surprise.
     */
    void syncCommands(NetworkCommandsPacket packet) {
        CommandManager manager = proxy.getCommandManager();
        Set<String> wanted = ConcurrentHashMap.newKeySet();

        for (NetworkCommandsPacket.Definition definition : packet.commands()) {
            String key = definition.name().toLowerCase(Locale.ROOT);
            wanted.add(key);
            definitions.put(key, definition);

            CommandMeta existing = registered.remove(key);
            if (existing != null) {
                manager.unregister(existing);
            }
            if (manager.hasCommand(definition.name())) {
                logger.warn("Not exposing network command /{}: this proxy already has a command by that name",
                        definition.name());
                continue;
            }

            List<String> aliases = definition.aliases().stream()
                    .filter(alias -> !manager.hasCommand(alias))
                    .toList();
            CommandMeta meta = manager.metaBuilder(definition.name())
                    .aliases(aliases.toArray(String[]::new))
                    .plugin(plugin)
                    .build();
            manager.register(meta, new BridgedCommand(key));
            registered.put(key, meta);
        }

        registered.keySet().removeIf(key -> {
            if (wanted.contains(key)) {
                return false;
            }
            manager.unregister(registered.get(key));
            definitions.remove(key);
            return true;
        });

        loginPermissions = List.copyOf(packet.loginPermissions());
        logger.info("Serving {} network command(s)", registered.size());
    }

    /** One network command, forwarded to the node on use. */
    private final class BridgedCommand implements SimpleCommand {

        private final String key;

        private BridgedCommand(String key) {
            this.key = key;
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            NetworkCommandsPacket.Definition definition = definitions.get(key);
            if (definition == null) {
                return false;
            }
            return definition.permission() == null || invocation.source().hasPermission(definition.permission());
        }

        @Override
        public void execute(Invocation invocation) {
            CommandSource source = invocation.source();
            if (!(source instanceof Player player)) {
                source.sendMessage(Component.text("Network commands run in game, or from the node console."));
                return;
            }
            send(player, invocation.arguments(), false);
        }

        @Override
        public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
            if (!(invocation.source() instanceof Player player)) {
                return CompletableFuture.completedFuture(List.of());
            }
            return send(player, invocation.arguments(), true)
                    .completeOnTimeout(List.of(), SUGGEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        }

        private CompletableFuture<List<String>> send(Player player, String[] arguments, boolean suggest) {
            NetworkCommandsPacket.Definition definition = definitions.get(key);
            Optional<NetworkChannel> channel = connection();
            if (definition == null || channel.isEmpty()) {
                if (!suggest) {
                    player.sendMessage(MINI.deserialize("<red>The network is not reachable right now."));
                }
                return CompletableFuture.completedFuture(List.of());
            }

            // Answered here because only the proxy knows what a player holds;
            // the node reads these back through CommandSender.hasPermission.
            Map<String, Boolean> permissions = new LinkedHashMap<>();
            if (definition.permission() != null) {
                permissions.put(definition.permission(), player.hasPermission(definition.permission()));
            }
            definition.extraPermissions().forEach(permission ->
                    permissions.put(permission, player.hasPermission(permission)));

            NetworkCommandPacket packet = new NetworkCommandPacket(
                    player.getUniqueId(), definition.name(), List.of(arguments), suggest, permissions);

            if (!suggest) {
                channel.get().send(packet);
                return CompletableFuture.completedFuture(List.of());
            }
            return channel.get().query(packet)
                    .thenApply(reply -> ((NetworkSuggestionsPacket) reply).suggestions())
                    .exceptionally(error -> List.of());
        }
    }

    // ----------------------------------------------------------------- logins

    /**
     * Asks the node whether a player may join, and applies the answer.
     *
     * <p>Fails open: if the node does not answer in time the player gets in.
     * The alternative is that a node restart locks every player out of the
     * network, which is a far worse outage than a banned player slipping in for
     * the few seconds the node was away.
     */
    CompletableFuture<Void> checkLogin(LoginEvent event) {
        Optional<NetworkChannel> channel = connection();
        if (channel.isEmpty() || !event.getResult().isAllowed()) {
            return CompletableFuture.completedFuture(null);
        }

        Player player = event.getPlayer();
        Map<String, Boolean> permissions = new LinkedHashMap<>();
        loginPermissions.forEach(permission -> permissions.put(permission, player.hasPermission(permission)));

        LoginCheckPacket packet = new LoginCheckPacket(player.getUniqueId(), player.getUsername(),
                player.getRemoteAddress().getAddress().getHostAddress(), permissions);

        return channel.get().query(packet)
                .orTimeout(LOGIN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .thenAccept(reply -> {
                    LoginVerdictPacket verdict = (LoginVerdictPacket) reply;
                    if (!verdict.allowed()) {
                        event.setResult(ResultedEvent.ComponentResult.denied(MINI.deserialize(verdict.reason())));
                    }
                })
                .exceptionally(error -> {
                    logger.warn("The node did not answer the login check for {} in time; letting them in",
                            player.getUsername());
                    return null;
                });
    }

    // ---------------------------------------------------------------- display

    void display(ProxyDisplayPacket packet) {
        this.display = packet.display();
        this.networkOnline = packet.networkOnline();
    }

    /** Applies the display and the whole network's counts to a server-list ping. */
    ServerPing applyTo(ServerPing ping) {
        ServerPing.Builder builder = ping.asBuilder()
                .onlinePlayers(online())
                .maximumPlayers(capacity.getAsInt());

        ProxyDisplay current = display;
        if (current != null) {
            if (!current.motd().isEmpty()) {
                builder.description(MINI.deserialize(fill(String.join("\n", current.motd()), null)));
            }
            if (!current.versionText().isBlank()) {
                // A protocol number no client speaks is how the version text is
                // shown in red, telling a player at a glance they cannot join.
                int protocol = current.versionBlocked() ? -1 : ping.getVersion().getProtocol();
                builder.version(new ServerPing.Version(protocol, current.versionText()));
            }
        }
        return builder.build();
    }

    /** Called on a timer: refreshes every player's tab list header and footer. */
    void refreshTablist() {
        ProxyDisplay current = display;
        if (current == null || (current.tablistHeader().isBlank() && current.tablistFooter().isBlank())) {
            return;
        }
        for (Player player : proxy.getAllPlayers()) {
            player.sendPlayerListHeaderAndFooter(
                    MINI.deserialize(fill(current.tablistHeader(), player)),
                    MINI.deserialize(fill(current.tablistFooter(), player)));
        }
    }

    /** The whole cloud's online count when the node has told us it, our own until then. */
    private int online() {
        int network = networkOnline;
        return network >= 0 ? Math.max(network, proxy.getPlayerCount()) : proxy.getPlayerCount();
    }

    /**
     * Fills in placeholders before MiniMessage sees the string.
     *
     * <p>Everything substituted here is a number or a name that cannot contain
     * a tag - server names and Minecraft usernames are restricted to safe
     * characters - so filling before parsing does not open an injection.
     */
    private String fill(String template, Player player) {
        String filled = template
                .replace("{online}", Integer.toString(online()))
                .replace("{max}", Integer.toString(capacity.getAsInt()))
                .replace("{proxy}", proxyName.get())
                .replace("{servers}", Integer.toString(servers.getAsInt()));
        if (player != null) {
            filled = filled
                    .replace("{player}", player.getUsername())
                    .replace("{ping}", Long.toString(player.getPing()))
                    .replace("{server}", player.getCurrentServer()
                            .map(server -> server.getServerInfo().getName()).orElse("-"));
        }
        return filled;
    }

    // --------------------------------------------------------------- messages

    /** Shows a message from the node: plain or MiniMessage, to one player or to everyone allowed. */
    void deliver(PlayerMessagePacket message) {
        Component text = message.rich()
                ? MINI.deserialize(message.message())
                : Component.text(message.message());
        if (!message.isBroadcast()) {
            proxy.getPlayer(message.playerId()).ifPresent(player -> player.sendMessage(text));
            return;
        }
        String permission = message.permission();
        List<Player> audience = new ArrayList<>(proxy.getAllPlayers());
        audience.stream()
                .filter(player -> permission == null || player.hasPermission(permission))
                .forEach(player -> player.sendMessage(text));
        if (permission != null) {
            // Staff-only traffic is exactly what the proxy console should see too.
            proxy.getConsoleCommandSource().sendMessage(text);
        }
    }

    private Optional<NetworkChannel> connection() {
        NetworkClient current = client.get();
        if (current == null) {
            return Optional.empty();
        }
        return current.channel().filter(NetworkChannel::authenticated);
    }
}
