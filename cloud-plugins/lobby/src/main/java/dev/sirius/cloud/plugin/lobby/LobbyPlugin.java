package dev.sirius.cloud.plugin.lobby;

import dev.sirius.cloud.api.driver.CloudDriver;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.plugin.lobby.menu.Menu;
import dev.sirius.cloud.plugin.lobby.menu.MenuListener;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A lobby for a SiriusCloud network.
 *
 * <p>Everything it shows comes from the cloud: which games exist and how many
 * play them, which lobbies there are, the player's history and rank. So it
 * belongs in the lobby group's template, where every lobby server - however
 * many autoscaling starts - is set up the same.
 */
public final class LobbyPlugin extends JavaPlugin implements Listener {

    private final NetworkView network = new NetworkView();
    private final Set<UUID> builders = ConcurrentHashMap.newKeySet();
    private Hotbar hotbar;
    private Visibility visibility;
    private Sidebar sidebar;
    private MovementListener movement;

    @Override
    public void onEnable() {
        if (!CloudDriver.isAvailable()) {
            getLogger().severe("SiriusCloud is not connected, so the lobby has nothing to show. It stays off.");
            return;
        }
        saveDefaultConfig();

        visibility = new Visibility(this);
        hotbar = new Hotbar(this);
        sidebar = new Sidebar(this);
        movement = new MovementListener(this);

        var manager = getServer().getPluginManager();
        manager.registerEvents(this, this);
        manager.registerEvents(hotbar, this);
        manager.registerEvents(new MenuListener(), this);
        manager.registerEvents(new ProtectionListener(this), this);
        manager.registerEvents(movement, this);

        applyWorldRules();
        network.refresh();

        // The network view is one shared refresh; menus and the sidebar only read it.
        Bukkit.getScheduler().runTaskTimer(this, network::refresh, 40L, 40L);
        Bukkit.getScheduler().runTaskTimer(this, sidebar::tick, 40L, 40L);
        Bukkit.getScheduler().runTaskTimer(this, this::refreshOpenMenus, 20L, 20L);

        // A reload with players online: set them up as if they had just joined.
        Bukkit.getOnlinePlayers().forEach(this::setUp);
        getLogger().info("Lobby ready on " + lobbyName() + " (group " + lobbyGroup() + ")");
    }

    @Override
    public void onDisable() {
        if (sidebar != null) {
            sidebar.hideAll();
        }
    }

    // ---------------------------------------------------------------- joining

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        if (getConfig().getBoolean("join.hide-join-quit-messages", true)) {
            event.joinMessage(null);
        }
        Player player = event.getPlayer();
        setUp(player);

        Map<String, Object> values = Map.of("player", player.getName(), "lobby", lobbyName(),
                "online", network.online());
        player.showTitle(Title.title(Text.render(getConfig().getString("join.title", ""), values),
                Text.render(getConfig().getString("join.subtitle", ""), values)));
        for (String line : getConfig().getStringList("join.message")) {
            player.sendMessage(Text.render(line, values));
        }
    }

    /** Spawn, a clean state, the hotbar and the sidebar. */
    private void setUp(Player player) {
        player.teleport(spawn());
        player.setGameMode(gameMode());
        var health = player.getAttribute(Attribute.MAX_HEALTH);
        player.setHealth(health == null ? 20 : health.getValue());
        player.setFoodLevel(20);
        player.setSaturation(20);
        player.setFireTicks(0);
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        hotbar.give(player);
        movement.prepare(player);
        visibility.joined(player);
        sidebar.show(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (getConfig().getBoolean("join.hide-join-quit-messages", true)) {
            event.quitMessage(null);
        }
        sidebar.remove(event.getPlayer());
        visibility.forget(event.getPlayer());
        builders.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        event.setRespawnLocation(spawn());
        Bukkit.getScheduler().runTask(this, () -> hotbar.give(event.getPlayer()));
    }

    // --------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "spawn" -> {
                if (sender instanceof Player player) {
                    player.teleport(spawn());
                }
            }
            case "setspawn" -> {
                if (sender instanceof Player player) {
                    Location at = player.getLocation();
                    getConfig().set("spawn", String.format(Locale.ROOT, "%s,%.2f,%.2f,%.2f,%.1f,%.1f",
                            at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch()));
                    saveConfig();
                    message(player, "spawn-set", Map.of());
                }
            }
            case "build" -> {
                if (sender instanceof Player player) {
                    if (builders.remove(player.getUniqueId())) {
                        player.setGameMode(gameMode());
                        hotbar.give(player);
                        movement.prepare(player);
                        message(player, "build-off", Map.of());
                    } else {
                        builders.add(player.getUniqueId());
                        player.getInventory().clear();
                        player.setGameMode(GameMode.CREATIVE);
                        message(player, "build-on", Map.of());
                    }
                }
            }
            case "lobbyreload" -> {
                reloadConfig();
                applyWorldRules();
                Bukkit.getOnlinePlayers().forEach(player -> {
                    if (!building(player)) {
                        hotbar.give(player);
                    }
                    sidebar.show(player);
                });
                sender.sendMessage(Text.render(getConfig().getString("messages.reloaded", "Reloaded.")));
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    // -------------------------------------------------------------- the world

    private void applyWorldRules() {
        long time = getConfig().getLong("protection.time", 6000);
        boolean weather = getConfig().getBoolean("protection.weather", false);
        boolean mobs = getConfig().getBoolean("protection.mobs", false);
        for (World world : Bukkit.getWorlds()) {
            // Time and weather only exist in overworld-like worlds; newer
            // Minecraft versions refuse to set them anywhere else.
            boolean overworld = world.getEnvironment() == World.Environment.NORMAL;
            try {
                if (overworld && time >= 0) {
                    world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
                    world.setTime(time);
                } else if (overworld) {
                    world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, true);
                }
                if (overworld && !weather) {
                    world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
                    world.setStorm(false);
                    world.setThundering(false);
                }
                world.setGameRule(GameRule.DO_MOB_SPAWNING, mobs);
                world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
            } catch (RuntimeException refused) {
                // One world refusing a rule is no reason for the lobby to stay off.
                getLogger().warning("Could not apply the lobby's world rules to " + world.getName() + ": "
                        + refused.getMessage());
            }
        }
    }

    /** The configured spawn, or the first world's. */
    public Location spawn() {
        String stored = getConfig().getString("spawn", "");
        if (stored != null && !stored.isBlank()) {
            String[] parts = stored.split(",");
            World world = parts.length >= 4 ? Bukkit.getWorld(parts[0]) : null;
            if (world != null) {
                try {
                    return new Location(world, Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                            Double.parseDouble(parts[3]), parts.length > 4 ? Float.parseFloat(parts[4]) : 0,
                            parts.length > 5 ? Float.parseFloat(parts[5]) : 0);
                } catch (NumberFormatException malformed) {
                    getLogger().warning("Ignoring an unreadable spawn in config.yml: " + stored);
                }
            }
        }
        return Bukkit.getWorlds().getFirst().getSpawnLocation().add(0.5, 0, 0.5);
    }

    private GameMode gameMode() {
        try {
            return GameMode.valueOf(getConfig().getString("join.gamemode", "ADVENTURE").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return GameMode.ADVENTURE;
        }
    }

    private void refreshOpenMenus() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Menu menu && menu.live()) {
                menu.render();
            }
        }
    }

    // ---------------------------------------------------------------- shared

    public NetworkView network() {
        return network;
    }

    public Hotbar hotbar() {
        return hotbar;
    }

    public Visibility visibility() {
        return visibility;
    }

    public Sidebar sidebar() {
        return sidebar;
    }

    public boolean building(Player player) {
        return builders.contains(player.getUniqueId());
    }

    /** This server's name in the cloud, e.g. {@code Lobby-2}. */
    public String lobbyName() {
        return CloudDriver.instance().services().self().map(ServiceInfo::name)
                .orElseGet(() -> connectionFile("serviceName", "Lobby"));
    }

    /**
     * From the file the wrapper wrote before starting this server. Needed only
     * in the first moments, before the connection to the node is up.
     */
    private String connectionFile(String key, String fallback) {
        java.nio.file.Path file = getServer().getWorldContainer().toPath().resolve("cloud-connection.json");
        try {
            var json = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(file)).getAsJsonObject();
            return json.has(key) ? json.get(key).getAsString() : fallback;
        } catch (java.io.IOException | RuntimeException unreadable) {
            return fallback;
        }
    }

    /** The group the lobby menu lists: configured, or this server's own. */
    public String lobbyGroup() {
        String configured = getConfig().getString("lobbies.group", "");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return CloudDriver.instance().services().self().map(ServiceInfo::groupName)
                .orElseGet(() -> connectionFile("groupName", "Lobby"));
    }

    public void message(Player player, String key, Map<String, ?> values) {
        String template = getConfig().getString("messages." + key);
        if (template != null && !template.isBlank()) {
            player.sendMessage(Text.render(template, new HashMap<>(values)));
        }
    }
}
