package dev.sirius.cloud.plugin.permissions;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sirius.cloud.api.driver.CloudDriver;
import dev.sirius.cloud.api.messaging.ChannelMessage;
import dev.sirius.cloud.api.permission.PermissionChannels;
import dev.sirius.cloud.api.permission.PermissionResolver;
import dev.sirius.cloud.api.permission.PermissionSnapshot;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Applies the cloud's permissions on this server.
 *
 * <p>Holds no data of its own. The node owns the groups and ranks and publishes
 * them; this plugin resolves and attaches them, and sends changes back. That is
 * what makes a rank granted on one server true on all of them, including the
 * ones that are empty right now.
 */
public final class PermissionsPlugin extends JavaPlugin implements Listener {

    private static final Gson GSON = new Gson();

    /** Ampersand codes in prefixes, so they can be typed in a command. */
    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacyAmpersand();

    private PermissionApplier applier;
    private RankDisplay display;
    private boolean luckPerms;

    /** The latest snapshot. Null until the node answers our first request. */
    private volatile PermissionSnapshot snapshot;

    /** Command replies we are waiting for, by request id. */
    private final Map<String, Consumer<String>> pending = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        if (!CloudDriver.isAvailable()) {
            getLogger().severe("SiriusCloud is not connected, so permissions cannot be applied.");
            getLogger().severe("This plugin does nothing without it.");
            return;
        }

        // With LuckPerms installed, it owns permissions on this server; this
        // plugin then only shows its ranks, in the format set on the node.
        luckPerms = getServer().getPluginManager().getPlugin("LuckPerms") != null;
        applier = new PermissionApplier(this);
        RankSource ranks = luckPerms
                ? new LuckPermsRanks(this, this::rankChanged)
                : new CloudRanks(() -> snapshot);
        display = new RankDisplay(() -> snapshot, ranks);
        // Left behind by a crash: the main scoreboard is saved with the world.
        display.removeAllTeams();

        CloudDriver.instance().messaging()
                .subscribe(PermissionChannels.SNAPSHOT, this::onSnapshot);
        CloudDriver.instance().messaging()
                .subscribe(PermissionChannels.RESULT, this::onResult);

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(display, this);

        var registered = getCommand("perms");
        if (registered != null && luckPerms) {
            registered.setExecutor((sender, command, label, args) -> {
                sender.sendMessage(legacy().deserialize(
                        "&cLuckPerms manages permissions on this server. Use &f/lp&c."));
                return true;
            });
        } else if (registered != null) {
            PermsCommand command = new PermsCommand(this);
            registered.setExecutor(command);
            registered.setTabCompleter(command);
        }

        startRequestRetry();
        if (luckPerms) {
            getLogger().info("LuckPerms is installed: it manages permissions, and its ranks are shown in chat,"
                    + " above heads and in the tab list.");
            // Shown with the default settings until the node's arrive.
            Bukkit.getOnlinePlayers().forEach(display::apply);
        } else {
            getLogger().info("Waiting for the cloud's permission data.");
        }
    }

    @Override
    public void onDisable() {
        if (applier != null) {
            applier.clear();
        }
        if (display != null) {
            display.removeAllTeams();
        }
        if (CloudDriver.isAvailable()) {
            CloudDriver.instance().messaging().unsubscribe(PermissionChannels.SNAPSHOT);
            CloudDriver.instance().messaging().unsubscribe(PermissionChannels.RESULT);
        }
    }

    /** Asks the node to send the current state. */
    void requestSnapshot() {
        CloudDriver.instance().messaging()
                .publish(PermissionChannels.REQUEST, "{}")
                .exceptionally(error -> {
                    // Not connected yet. The retry task is what fixes this, so
                    // there is nothing to do here but not throw.
                    return null;
                });
    }

    /**
     * Keeps asking until the node answers.
     *
     * <p>One request on enable is not enough, and this is not theoretical: the
     * core plugin opens its connection asynchronously, so the first request
     * usually goes out before there is a socket to send it on and is dropped.
     * Without a retry the server sits with no permissions at all until somebody
     * happens to run a command or change something on another server, which is
     * a long time to have every player holding nothing.
     */
    private void startRequestRetry() {
        int[] attempts = {0};
        Bukkit.getScheduler().runTaskTimer(this, task -> {
            if (snapshot != null) {
                task.cancel();
                return;
            }
            // With LuckPerms only the display settings are missing, and the
            // node may simply not run the permissions module: stop asking.
            if (luckPerms && ++attempts[0] > 20) {
                getLogger().info("No display settings from the node; using the defaults. Enable its permissions"
                        + " module to change them.");
                task.cancel();
                return;
            }
            requestSnapshot();
        }, 20L, 20L * 3);
    }

    private void onSnapshot(ChannelMessage message) {
        PermissionSnapshot incoming;
        try {
            incoming = GSON.fromJson(message.payload(), PermissionSnapshot.class);
        } catch (RuntimeException exception) {
            getLogger().warning("Ignoring an unreadable permission snapshot: " + exception.getMessage());
            return;
        }
        if (incoming == null) {
            return;
        }

        // Delivery is unordered, so an older snapshot can arrive after a newer
        // one. Applying it would silently undo the most recent change.
        PermissionSnapshot current = this.snapshot;
        if (current != null && incoming.revision() < current.revision()) {
            return;
        }

        boolean first = current == null;
        this.snapshot = incoming;

        // Names as well as permissions: a rank change has to show in the tab
        // list straight away, not the next time the player happens to rejoin.
        // Both touch Bukkit state, and snapshots arrive on a Netty thread.
        Bukkit.getScheduler().runTask(this, () -> {
            Bukkit.getOnlinePlayers().forEach(player -> {
                if (!luckPerms) {
                    applier.apply(player, incoming);
                }
                display.apply(player);
            });
            display.pruneEmptyTeams();
        });

        if (first) {
            getLogger().info("Applied cloud permissions: "
                    + incoming.groups().size() + " group(s) at revision " + incoming.revision());
        }
    }

    private void onResult(ChannelMessage message) {
        JsonObject body;
        try {
            body = JsonParser.parseString(message.payload()).getAsJsonObject();
        } catch (RuntimeException exception) {
            return;
        }
        String id = body.has("requestId") ? body.get("requestId").getAsString() : "";

        // Every server sees every reply, so an id we did not issue belongs to
        // somebody else's command and is not ours to report.
        Consumer<String> waiting = pending.remove(id);
        if (waiting == null) {
            return;
        }
        waiting.accept(body.has("message") ? body.get("message").getAsString() : "Done");
    }

    /** Sends a mutation and routes the node's reply back to whoever asked. */
    void mutate(JsonObject request, Consumer<String> reply) {
        String id = UUID.randomUUID().toString();
        request.addProperty("requestId", id);
        pending.put(id, reply);

        // Nothing queues a reply that never comes, so the entry is dropped
        // after a grace period rather than leaking one per failed command.
        Bukkit.getScheduler().runTaskLater(this, () -> pending.remove(id), 20L * 15);

        CloudDriver.instance().messaging().publish(PermissionChannels.MUTATE, GSON.toJson(request));
    }

    PermissionSnapshot snapshot() {
        return snapshot;
    }

    /** LuckPerms recalculated a player, or everyone when {@code player} is null. Main thread. */
    private void rankChanged(org.bukkit.entity.Player player) {
        if (player == null) {
            Bukkit.getOnlinePlayers().forEach(display::apply);
            display.pruneEmptyTeams();
        } else {
            display.apply(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        if (luckPerms) {
            // LuckPerms has the player loaded by now, and the display works
            // with default settings until the node's arrive.
            display.apply(event.getPlayer());
            return;
        }
        PermissionSnapshot current = snapshot;
        if (current == null) {
            // The node has not answered yet. Ask again rather than leaving this
            // player with nothing until the next change happens to arrive.
            requestSnapshot();
            return;
        }
        applier.apply(event.getPlayer(), current);
        display.apply(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        applier.forget(event.getPlayer().getUniqueId());
        display.forget(event.getPlayer());
    }

    static LegacyComponentSerializer legacy() {
        return LEGACY;
    }
}
