package dev.sirius.cloud.node.gateway;

import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.network.CommandSender;
import dev.sirius.cloud.api.network.LoginAttempt;
import dev.sirius.cloud.api.network.LoginFilter;
import dev.sirius.cloud.api.network.NetworkCommand;
import dev.sirius.cloud.api.network.NetworkProvider;
import dev.sirius.cloud.api.network.ProxyDisplay;
import dev.sirius.cloud.api.network.Text;
import dev.sirius.cloud.api.player.CloudPlayer;
import dev.sirius.cloud.api.service.ServiceType;
import dev.sirius.cloud.node.player.PlayerManager;
import dev.sirius.cloud.node.player.PlayerRegistry;
import dev.sirius.cloud.node.service.ServiceChannelRegistry;
import dev.sirius.cloud.node.service.ServiceRegistry;
import dev.sirius.cloud.protocol.connection.NetworkChannel;
import dev.sirius.cloud.protocol.packet.impl.ChatRestrictionsPacket;
import dev.sirius.cloud.protocol.packet.impl.LoginCheckPacket;
import dev.sirius.cloud.protocol.packet.impl.LoginVerdictPacket;
import dev.sirius.cloud.protocol.packet.impl.NetworkCommandPacket;
import dev.sirius.cloud.protocol.packet.impl.NetworkCommandsPacket;
import dev.sirius.cloud.protocol.packet.impl.ProxyDisplayPacket;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The node's {@link NetworkProvider}: owns the commands, login filters, display
 * and chat restrictions, and keeps every proxy and server in step with them.
 *
 * <p>Proxies are deliberately generic. They register whatever this tells them,
 * forward each use back here, and render what they are sent - so a module can
 * add {@code /party} to a cloud of five proxies without any of them changing.
 *
 * <p>Command bodies, suggestions and login filters all run on virtual threads.
 * They belong to modules, which reach the database, and a module blocking on a
 * query must never stall the network thread that delivered the request.
 */
public final class ProxyGateway implements NetworkProvider {

    private static final CloudLogger LOGGER = CloudLogger.of("Network");

    private final ServiceRegistry services;
    private final ServiceChannelRegistry channels;
    private final PlayerRegistry players;
    private final PlayerManager playerManager;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** Keyed by lowercased name. Aliases are resolved on lookup, not stored twice. */
    private final Map<String, NetworkCommand> commands = new ConcurrentHashMap<>();
    private final List<LoginFilter> filters = new CopyOnWriteArrayList<>();
    private final Map<UUID, ChatRestrictionsPacket.Restriction> restrictions = new ConcurrentHashMap<>();

    private volatile ProxyDisplay display;

    /**
     * Set when the online count or the display changed, cleared when proxies
     * were told. Pushed on a one-second tick rather than per change, because a
     * wave of joins would otherwise send every proxy one packet per player.
     */
    private final AtomicBoolean displayDirty = new AtomicBoolean();

    public ProxyGateway(ServiceRegistry services,
                        ServiceChannelRegistry channels,
                        PlayerRegistry players,
                        PlayerManager playerManager) {
        this.services = services;
        this.channels = channels;
        this.players = players;
        this.playerManager = playerManager;
    }

    // --------------------------------------------------------------- commands

    @Override
    public void registerCommand(NetworkCommand command) {
        if (command.name() == null || command.name().isBlank() || command.name().contains(" ")) {
            throw new IllegalArgumentException("A network command needs a one-word name");
        }
        commands.put(key(command.name()), command);
        syncCommands();
    }

    @Override
    public void unregisterCommand(String name) {
        if (commands.remove(key(name)) != null) {
            syncCommands();
        }
    }

    @Override
    public void registerLoginFilter(LoginFilter filter) {
        filters.add(filter);
        // Its permissions have to reach the proxies, which evaluate them.
        syncCommands();
    }

    @Override
    public void unregisterLoginFilter(LoginFilter filter) {
        if (filters.remove(filter)) {
            syncCommands();
        }
    }

    /** Resolves a name or an alias. */
    public Optional<NetworkCommand> command(String name) {
        String wanted = key(name);
        NetworkCommand direct = commands.get(wanted);
        if (direct != null) {
            return Optional.of(direct);
        }
        return commands.values().stream()
                .filter(command -> command.aliases().stream().anyMatch(alias -> key(alias).equals(wanted)))
                .findFirst();
    }

    /** Name to description, for the node console's completion. */
    public Map<String, String> describeCommands() {
        Map<String, String> described = new LinkedHashMap<>();
        commands.values().forEach(command -> described.put(command.name(), command.description()));
        return described;
    }

    /** Runs a command a player typed on a proxy. */
    public void execute(NetworkCommandPacket packet) {
        Optional<NetworkCommand> command = command(packet.command());
        Optional<CloudPlayer> player = players.byId(packet.playerId());
        if (command.isEmpty() || player.isEmpty()) {
            // Either the command was unregistered after the proxy last synced,
            // or the player left in between. Nothing sensible to run either way.
            return;
        }
        CommandSender sender = new PlayerSender(player.get(), packet.permissions(), playerManager);
        String[] args = packet.arguments().toArray(String[]::new);
        executor.execute(() -> run(command.get(), sender, args));
    }

    /** Tab completions for a player. */
    public CompletableFuture<List<String>> suggest(NetworkCommandPacket packet) {
        Optional<NetworkCommand> command = command(packet.command());
        Optional<CloudPlayer> player = players.byId(packet.playerId());
        if (command.isEmpty() || player.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        CommandSender sender = new PlayerSender(player.get(), packet.permissions(), playerManager);
        String[] args = packet.arguments().toArray(String[]::new);
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<String> suggestions = command.get().suggest(sender, args);
                return suggestions == null ? List.<String>of() : suggestions;
            } catch (Exception exception) {
                LOGGER.debug("Suggestions for /{} failed: {}", packet.command(), exception.getMessage());
                return List.<String>of();
            }
        }, executor);
    }

    /**
     * Runs a network command from the node console, if one has this name.
     *
     * @return whether a command was found
     */
    public boolean executeConsole(String name, String[] args) {
        Optional<NetworkCommand> command = command(name);
        if (command.isEmpty()) {
            return false;
        }
        executor.execute(() -> run(command.get(), ConsoleSender.INSTANCE, args));
        return true;
    }

    public List<String> suggestConsole(String name, String[] args) {
        return command(name).map(command -> {
            try {
                List<String> suggestions = command.suggest(ConsoleSender.INSTANCE, args);
                return suggestions == null ? List.<String>of() : suggestions;
            } catch (Exception exception) {
                return List.<String>of();
            }
        }).orElse(List.of());
    }

    private static void run(NetworkCommand command, CommandSender sender, String[] args) {
        try {
            command.execute(sender, args);
        } catch (Exception exception) {
            // The player sees that it failed; the details go to the node log,
            // which is where somebody who can fix it will be looking.
            LOGGER.error("Network command /" + command.name() + " failed for " + sender.name(), exception);
            sender.sendMessage("<red>Something went wrong running that command.");
        }
    }

    // ----------------------------------------------------------------- logins

    /**
     * Runs every login filter; the first refusal wins.
     *
     * <p>A filter that throws is skipped rather than treated as a refusal. A
     * bug in one module must not be able to lock the whole network out.
     */
    public CompletableFuture<LoginVerdictPacket> checkLogin(LoginCheckPacket packet, String proxyName) {
        if (filters.isEmpty()) {
            return CompletableFuture.completedFuture(new LoginVerdictPacket(true, ""));
        }
        LoginAttempt attempt = new LoginAttempt(
                packet.playerId(), packet.name(), packet.address(), proxyName, packet.permissions());
        return CompletableFuture.supplyAsync(() -> {
            for (LoginFilter filter : filters) {
                try {
                    Optional<String> reason = filter.check(attempt);
                    if (reason != null && reason.isPresent()) {
                        LOGGER.info("Refused {} at login: {}", packet.name(), Text.plain(reason.get()));
                        return new LoginVerdictPacket(false, reason.get());
                    }
                } catch (Exception exception) {
                    LOGGER.error("A login filter failed for " + packet.name() + "; letting them through", exception);
                }
            }
            return new LoginVerdictPacket(true, "");
        }, executor);
    }

    // ---------------------------------------------------------------- display

    @Override
    public void display(ProxyDisplay display) {
        this.display = display;
        displayDirty.set(true);
    }

    @Override
    public Optional<ProxyDisplay> display() {
        return Optional.ofNullable(display);
    }

    /** Called when the cloud's online count changes. */
    public void onlineChanged() {
        displayDirty.set(true);
    }

    /** Once a second: tells proxies about display or online-count changes, if there were any. */
    public void tick() {
        if (displayDirty.getAndSet(false)) {
            channels.broadcastToProxies(services, displayPacket());
        }
    }

    // ------------------------------------------------------------------- chat

    @Override
    public CompletableFuture<Void> restrictChat(UUID player, long untilMillis, String reason) {
        restrictions.put(player, new ChatRestrictionsPacket.Restriction(
                player, untilMillis, reason == null ? "" : reason));
        pushRestrictions();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> liftChatRestriction(UUID player) {
        if (restrictions.remove(player) != null) {
            pushRestrictions();
        }
        return CompletableFuture.completedFuture(null);
    }

    private void pushRestrictions() {
        ChatRestrictionsPacket packet = restrictionsPacket();
        for (UUID serviceId : channels.connectedServices()) {
            services.byId(serviceId)
                    .filter(service -> service.type() == ServiceType.SERVER)
                    .ifPresent(service -> channels.sendTo(serviceId, packet));
        }
    }

    private ChatRestrictionsPacket restrictionsPacket() {
        long now = System.currentTimeMillis();
        // Expired ones are dropped as they are sent: nothing else would ever
        // clear a timed mute that lapsed while nobody touched it.
        restrictions.values().removeIf(restriction ->
                restriction.untilMillis() > 0 && restriction.untilMillis() <= now);
        return new ChatRestrictionsPacket(new ArrayList<>(restrictions.values()));
    }

    // ---------------------------------------------------------------- syncing

    /** Everything a proxy needs, sent when it connects. */
    public void syncProxy(NetworkChannel proxy) {
        proxy.send(commandsPacket());
        proxy.send(displayPacket());
    }

    /** Everything a server needs, sent when it connects. */
    public void syncServer(NetworkChannel server) {
        server.send(restrictionsPacket());
    }

    private void syncCommands() {
        channels.broadcastToProxies(services, commandsPacket());
    }

    private NetworkCommandsPacket commandsPacket() {
        List<NetworkCommandsPacket.Definition> definitions = commands.values().stream()
                .map(command -> new NetworkCommandsPacket.Definition(
                        command.name(),
                        List.copyOf(command.aliases()),
                        command.permission(),
                        command.description(),
                        List.copyOf(command.extraPermissions())))
                .toList();

        Set<String> loginPermissions = new LinkedHashSet<>();
        filters.forEach(filter -> loginPermissions.addAll(filter.permissions()));

        return new NetworkCommandsPacket(definitions, new ArrayList<>(loginPermissions));
    }

    private ProxyDisplayPacket displayPacket() {
        return new ProxyDisplayPacket(display, players.count());
    }

    public Collection<String> commandNames() {
        return commands.values().stream().map(NetworkCommand::name).toList();
    }

    private static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- senders

    /** A player, answered through their proxy. */
    private record PlayerSender(CloudPlayer player, Map<String, Boolean> permissions,
                                PlayerManager playerManager) implements CommandSender {

        @Override
        public String name() {
            return player.name();
        }

        @Override
        public Optional<UUID> uniqueId() {
            return Optional.of(player.uniqueId());
        }

        @Override
        public boolean isConsole() {
            return false;
        }

        @Override
        public boolean hasPermission(String permission) {
            return Boolean.TRUE.equals(permissions.get(permission));
        }

        @Override
        public void sendMessage(String miniMessage) {
            playerManager.sendRichMessage(player.uniqueId(), miniMessage);
        }
    }

    /**
     * The node console: holds every permission, reads plain text.
     *
     * <p>A class rather than an enum singleton: {@code Enum.name()} is final and
     * would satisfy {@link CommandSender#name()} by returning "INSTANCE".
     */
    private static final class ConsoleSender implements CommandSender {

        static final ConsoleSender INSTANCE = new ConsoleSender();

        @Override
        public String name() {
            return "Console";
        }

        @Override
        public boolean isConsole() {
            return true;
        }

        @Override
        public Optional<UUID> uniqueId() {
            return Optional.empty();
        }

        @Override
        public boolean hasPermission(String permission) {
            return true;
        }

        @Override
        public void sendMessage(String miniMessage) {
            CloudLogger.raw(Text.plain(miniMessage));
        }
    }
}
