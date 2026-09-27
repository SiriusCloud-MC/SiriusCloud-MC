package dev.sirius.cloud.module.moderation;

import com.google.gson.Gson;
import dev.sirius.cloud.api.database.DatabaseCollection;
import dev.sirius.cloud.api.driver.CloudDriver;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.module.CloudModule;
import dev.sirius.cloud.api.module.ModuleContext;
import dev.sirius.cloud.api.network.CommandSender;
import dev.sirius.cloud.api.network.LoginAttempt;
import dev.sirius.cloud.api.network.LoginFilter;
import dev.sirius.cloud.api.network.NetworkCommand;
import dev.sirius.cloud.api.network.Text;
import dev.sirius.cloud.api.player.CloudPlayer;
import dev.sirius.cloud.module.common.Commands;
import dev.sirius.cloud.module.common.Durations;
import dev.sirius.cloud.module.common.ModuleConfigs;
import dev.sirius.cloud.module.common.Players;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Bans, mutes, kicks and the tools staff use to find people.
 *
 * <p>Bans are enforced at login, through the node's login filter, so a banned
 * player never reaches a server. Mutes are enforced by the servers themselves,
 * because signed chat cannot be stopped on a proxy without disconnecting the
 * player; this module tells the network who is muted, and re-tells it on every
 * start, so a node restart does not quietly unmute anybody.
 *
 * <p>Both persist in the cloud's database, so they hold across restarts and
 * across every proxy - which is the reason to do this on the node at all
 * rather than per server.
 */
public final class ModerationModule implements CloudModule {

    private static final CloudLogger LOGGER = CloudLogger.of("Moderation");
    private static final Gson GSON = new Gson();

    private CloudDriver driver;
    private ModerationConfig config;
    private DatabaseCollection bans;
    private DatabaseCollection mutes;
    private final List<String> registered = new ArrayList<>();

    private final LoginFilter banGate = attempt -> activeBan(attempt).map(this::banMessage);

    @Override
    public void onEnable(ModuleContext context) {
        this.driver = context.driver();
        Path configFile = context.dataDirectory().resolve("config.json");
        try {
            this.config = ModuleConfigs.load(configFile, ModerationConfig.class, ModerationConfig::new);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read " + configFile + ": " + exception.getMessage());
        }
        this.bans = driver.database().collection("bans");
        this.mutes = driver.database().collection("mutes");

        if (config.bans()) {
            driver.network().registerLoginFilter(banGate);
            register(Commands.named("ban").permission("siriuscloud.ban")
                    .description("Ban a player from the network, optionally for a time")
                    .executes(this::ban).suggests(this::names).build());
            register(Commands.named("unban").permission("siriuscloud.unban")
                    .description("Lift a ban").executes(this::unban).build());
        }
        if (config.mutes()) {
            restoreMutes();
            register(Commands.named("mute").permission("siriuscloud.mute")
                    .description("Stop a player chatting anywhere, optionally for a time")
                    .executes(this::mute).suggests(this::names).build());
            register(Commands.named("unmute").permission("siriuscloud.unmute")
                    .description("Lift a mute").executes(this::unmute).suggests(this::names).build());
        }
        if (config.kicks()) {
            register(Commands.named("kick").aliases("netkick").permission("siriuscloud.kick")
                    .description("Disconnect a player from the network")
                    .executes(this::kick).suggests(this::names).build());
        }
        if (config.staffChat()) {
            register(Commands.named("staffchat").aliases("schat", "stc").permission("siriuscloud.staffchat")
                    .description("Talk to all staff, wherever they are").executes(this::staffChat).build());
        }
        if (config.find()) {
            register(Commands.named("find").permission("siriuscloud.find")
                    .description("Which server a player is on").executes(this::find).suggests(this::names).build());
        }
        if (config.jump()) {
            register(Commands.named("jump").aliases("goto").permission("siriuscloud.jump")
                    .description("Go to the server a player is on").executes(this::jump).suggests(this::names).build());
        }
        LOGGER.info("Serving {} moderation command(s)", registered.size());
    }

    @Override
    public void onDisable() {
        registered.forEach(driver.network()::unregisterCommand);
        registered.clear();
        driver.network().unregisterLoginFilter(banGate);
    }

    private void register(NetworkCommand command) {
        driver.network().registerCommand(command);
        registered.add(command.name());
    }

    // ------------------------------------------------------------------- bans

    private Optional<Punishment> activeBan(LoginAttempt attempt) {
        return load(bans, attempt.uniqueId()).filter(ban -> {
            if (ban.expired()) {
                // Lapsed: cleaned up the first time it would have mattered.
                bans.delete(attempt.uniqueId().toString());
                return false;
            }
            return true;
        });
    }

    private String banMessage(Punishment ban) {
        return config.banMessage()
                .replace("{reason}", Text.escape(ban.reason))
                .replace("{expires}", ban.describeExpiry())
                .replace("{by}", Text.escape(ban.by));
    }

    private void ban(CommandSender sender, String[] args) {
        Optional<Target> target = target(sender, args, "/ban <player> [duration] [reason]");
        if (target.isEmpty()) {
            return;
        }
        Punishment ban = Punishment.of(target.get().player().uniqueId(), target.get().player().name(),
                target.get().reason(), sender.name(), target.get().length());
        bans.put(ban.uuid, GSON.toJson(ban)).join();

        driver.players().cachedPlayer(target.get().player().uniqueId())
                .ifPresent(online -> driver.players().kick(online.uniqueId(), Text.plain(banMessage(ban))));

        String summary = target.get().player().name() + " was banned by " + sender.name()
                + (ban.expires == 0 ? " permanently" : " for " + Durations.describe(target.get().length()))
                + ": " + ban.reason;
        LOGGER.info("{}", summary);
        staffNotice("<red>" + Text.escape(summary));
        if (sender.isConsole()) {
            sender.sendMessage(summary);
        }
    }

    private void unban(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("<red>Usage: /unban <player>");
            return;
        }
        Optional<Players.Known> player = Players.find(driver, args[0]);
        if (player.isEmpty() || !bans.delete(player.get().uniqueId().toString()).join()) {
            sender.sendMessage("<red>" + Text.escape(args[0]) + " is not banned.");
            return;
        }
        LOGGER.info("{} unbanned {}", sender.name(), player.get().name());
        staffNotice("<green>" + player.get().name() + " was unbanned by " + Text.escape(sender.name()) + ".");
        if (sender.isConsole()) {
            sender.sendMessage(player.get().name() + " was unbanned.");
        }
    }

    // ------------------------------------------------------------------ mutes

    /** Re-applies every stored mute, so a node restart does not quietly unmute anybody. */
    private void restoreMutes() {
        Map<String, String> stored = mutes.all().join();
        int restored = 0;
        for (Map.Entry<String, String> entry : stored.entrySet()) {
            Punishment mute = GSON.fromJson(entry.getValue(), Punishment.class);
            if (mute.expired()) {
                mutes.delete(entry.getKey());
                continue;
            }
            driver.network().restrictChat(UUID.fromString(mute.uuid), mute.expires, mute.reason);
            restored++;
        }
        if (restored > 0) {
            LOGGER.info("Restored {} active mute(s)", restored);
        }
    }

    private void mute(CommandSender sender, String[] args) {
        Optional<Target> target = target(sender, args, "/mute <player> [duration] [reason]");
        if (target.isEmpty()) {
            return;
        }
        Punishment mute = Punishment.of(target.get().player().uniqueId(), target.get().player().name(),
                target.get().reason(), sender.name(), target.get().length());
        mutes.put(mute.uuid, GSON.toJson(mute)).join();
        driver.network().restrictChat(target.get().player().uniqueId(), mute.expires, mute.reason);

        driver.players().sendRichMessage(target.get().player().uniqueId(), "<red>You were muted "
                + (mute.expires == 0 ? "permanently" : "for " + Durations.describe(target.get().length()))
                + ". <gray>(" + Text.escape(mute.reason) + ")").exceptionally(error -> null);

        String summary = target.get().player().name() + " was muted by " + sender.name()
                + (mute.expires == 0 ? " permanently" : " for " + Durations.describe(target.get().length()))
                + ": " + mute.reason;
        LOGGER.info("{}", summary);
        staffNotice("<gold>" + Text.escape(summary));
        if (sender.isConsole()) {
            sender.sendMessage(summary);
        }
    }

    private void unmute(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("<red>Usage: /unmute <player>");
            return;
        }
        Optional<Players.Known> player = Players.find(driver, args[0]);
        if (player.isEmpty() || !mutes.delete(player.get().uniqueId().toString()).join()) {
            sender.sendMessage("<red>" + Text.escape(args[0]) + " is not muted.");
            return;
        }
        driver.network().liftChatRestriction(player.get().uniqueId());
        driver.players().sendRichMessage(player.get().uniqueId(), "<green>You can chat again.")
                .exceptionally(error -> null);
        staffNotice("<green>" + player.get().name() + " was unmuted by " + Text.escape(sender.name()) + ".");
        if (sender.isConsole()) {
            sender.sendMessage(player.get().name() + " was unmuted.");
        }
    }

    // ------------------------------------------------------------------ other

    private void kick(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("<red>Usage: /kick <player> [reason]");
            return;
        }
        Optional<CloudPlayer> player = driver.players().cachedPlayer(args[0]);
        if (player.isEmpty()) {
            sender.sendMessage("<red>" + Text.escape(args[0]) + " is not online.");
            return;
        }
        String reason = args.length > 1 ? Commands.rest(args, 1) : "No reason given";
        driver.players().kick(player.get().uniqueId(),
                Text.plain(config.kickMessage().replace("{reason}", Text.escape(reason))));
        staffNotice("<yellow>" + player.get().name() + " was kicked by " + Text.escape(sender.name())
                + ": " + Text.escape(reason));
        if (sender.isConsole()) {
            sender.sendMessage(player.get().name() + " was kicked.");
        }
    }

    private void staffChat(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("<red>Usage: /staffchat <message>");
            return;
        }
        String server = sender.uniqueId().flatMap(driver.players()::cachedPlayer)
                .flatMap(CloudPlayer::serverName).orElse(sender.isConsole() ? "node" : "-");
        String line = config.staffChatFormat()
                .replace("{player}", Text.escape(sender.name()))
                .replace("{server}", Text.escape(server))
                .replace("{message}", Text.escape(Commands.rest(args, 0)));
        driver.players().broadcastRich(line, "siriuscloud.staffchat");
        LOGGER.info("[Staff] {}: {}", sender.name(), Commands.rest(args, 0));
    }

    private void find(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("<red>Usage: /find <player>");
            return;
        }
        Optional<CloudPlayer> player = driver.players().cachedPlayer(args[0]);
        sender.sendMessage(player.map(found -> "<gray>" + found.name() + " is on <white>"
                        + found.serverName().orElse("(connecting)") + "<gray> via " + found.proxyName() + ".")
                .orElse("<red>" + Text.escape(args[0]) + " is not online."));
    }

    private void jump(CommandSender sender, String[] args) {
        Optional<UUID> self = sender.uniqueId();
        if (self.isEmpty()) {
            sender.sendMessage("Only players can jump.");
            return;
        }
        if (args.length < 1) {
            sender.sendMessage("<red>Usage: /jump <player>");
            return;
        }
        Optional<String> server = driver.players().cachedPlayer(args[0]).flatMap(CloudPlayer::serverName);
        if (server.isEmpty()) {
            sender.sendMessage("<red>" + Text.escape(args[0]) + " is not on a server.");
            return;
        }
        driver.players().connect(self.get(), server.get())
                .thenRun(() -> sender.sendMessage("<gray>Jumping to <white>" + server.get() + "<gray>."))
                .exceptionally(error -> {
                    sender.sendMessage("<red>Could not jump: " + Text.escape(error.getMessage()));
                    return null;
                });
    }

    // ---------------------------------------------------------------- helpers

    /** The player, length and reason from {@code <player> [duration] [reason...]}. */
    private record Target(Players.Known player, Duration length, String reason) {
    }

    private Optional<Target> target(CommandSender sender, String[] args, String usage) {
        if (args.length < 1) {
            sender.sendMessage("<red>Usage: " + usage);
            return Optional.empty();
        }
        Optional<Players.Known> player = Players.find(driver, args[0]);
        if (player.isEmpty()) {
            // No UUID to attach it to. A ban by name alone would miss them the
            // moment they changed it, and bind whoever took the name next.
            sender.sendMessage("<red>Nobody called " + Text.escape(args[0]) + " has played here.");
            return Optional.empty();
        }
        if (sender.uniqueId().map(player.get().uniqueId()::equals).orElse(false)) {
            sender.sendMessage("<red>You cannot do that to yourself.");
            return Optional.empty();
        }

        Duration length = Durations.PERMANENT;
        int reasonFrom = 1;
        if (args.length > 1) {
            Optional<Duration> parsed = Durations.parse(args[1]);
            if (parsed.isPresent()) {
                length = parsed.get();
                reasonFrom = 2;
            }
        }
        String reason = Commands.rest(args, reasonFrom);
        return Optional.of(new Target(player.get(), length, reason.isBlank() ? "No reason given" : reason));
    }

    private static Optional<Punishment> load(DatabaseCollection collection, UUID player) {
        return collection.get(player.toString()).join().map(json -> GSON.fromJson(json, Punishment.class));
    }

    private void staffNotice(String message) {
        driver.players().broadcastRich("<dark_gray>[<red>Mod<dark_gray>] " + message, "siriuscloud.staffchat");
    }

    private List<String> names(CommandSender sender, String[] args) {
        return args.length <= 1 ? Commands.matching(Players.onlineNames(driver), args) : List.of();
    }
}
