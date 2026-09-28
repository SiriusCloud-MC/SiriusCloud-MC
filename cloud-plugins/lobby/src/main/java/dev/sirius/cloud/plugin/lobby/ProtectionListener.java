package dev.sirius.cloud.plugin.lobby;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.weather.WeatherChangeEvent;

/**
 * Keeps the lobby the way it was built. Everything here can be turned off in
 * the config, and players in build mode are exempt from the building rules.
 */
public final class ProtectionListener implements Listener {

    private final LobbyPlugin plugin;

    public ProtectionListener(LobbyPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean allowed(String rule) {
        return plugin.getConfig().getBoolean("protection." + rule, false);
    }

    private boolean building(Player player) {
        return plugin.building(player);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player && !allowed("damage")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (!allowed("hunger")) {
            event.setCancelled(true);
            event.getEntity().setFoodLevel(20);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!allowed("build") && !building(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!allowed("build") && !building(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (building(event.getPlayer()) || allowed("build")) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        // Trampling farmland, and opening chests, doors and the like.
        if (event.getAction() == Action.PHYSICAL && block.getType().name().equals("FARMLAND")) {
            event.setCancelled(true);
        } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK && usable(block)
                && plugin.hotbar().id(event.getItem()) == null) {
            event.setCancelled(true);
        }
    }

    /** Chests, doors, trapdoors, gates, buttons and levers: things a click would change. */
    private static boolean usable(Block block) {
        String type = block.getType().name();
        return block.getState() instanceof org.bukkit.inventory.InventoryHolder
                || block.getBlockData() instanceof org.bukkit.block.data.Openable
                || type.endsWith("_BUTTON") || type.equals("LEVER") || type.endsWith("_BED")
                || type.equals("REPEATER") || type.equals("COMPARATOR") || type.endsWith("NOTE_BLOCK");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!allowed("drop-items") && !building(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventory(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && !building(player)
                && event.getClickedInventory() == player.getInventory()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (!building(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onWeather(WeatherChangeEvent event) {
        if (!allowed("weather") && event.toWeatherState()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (!allowed("mobs") && event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NATURAL) {
            event.setCancelled(true);
        }
    }
}
