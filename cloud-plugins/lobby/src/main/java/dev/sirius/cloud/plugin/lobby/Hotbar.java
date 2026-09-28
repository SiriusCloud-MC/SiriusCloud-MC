package dev.sirius.cloud.plugin.lobby;

import dev.sirius.cloud.plugin.lobby.menu.GameMenu;
import dev.sirius.cloud.plugin.lobby.menu.LobbyMenu;
import dev.sirius.cloud.plugin.lobby.menu.Menu;
import dev.sirius.cloud.plugin.lobby.menu.ProfileMenu;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Map;

/**
 * The items every player holds in the lobby, and what they open.
 *
 * <p>Items are recognised by a tag stored on them, not by their name or
 * material, so renaming one in the config never breaks it.
 */
public final class Hotbar implements Listener {

    private final LobbyPlugin plugin;
    private final NamespacedKey key;

    public Hotbar(LobbyPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "item");
    }

    public void give(Player player) {
        FileConfiguration config = plugin.getConfig();
        player.getInventory().clear();
        put(player, "games", config.getInt("hotbar.games.slot", 0),
                material(config.getString("hotbar.games.material"), Material.COMPASS),
                config.getString("hotbar.games.name", "Games"));
        put(player, "lobbies", config.getInt("hotbar.lobbies.slot", 1),
                material(config.getString("hotbar.lobbies.material"), Material.NETHER_STAR),
                config.getString("hotbar.lobbies.name", "Lobbies"));

        ItemStack head = tagged("profile", Material.PLAYER_HEAD, config.getString("hotbar.profile.name", "Profile"));
        if (head.getItemMeta() instanceof SkullMeta skull) {
            skull.setOwningPlayer(player);
            head.setItemMeta(skull);
        }
        player.getInventory().setItem(config.getInt("hotbar.profile.slot", 4), head);
        updateVisibility(player);
    }

    /** Shows whether this player currently sees others. */
    public void updateVisibility(Player player) {
        FileConfiguration config = plugin.getConfig();
        boolean hidden = plugin.visibility().hides(player);
        put(player, "visibility", config.getInt("hotbar.visibility.slot", 8),
                material(config.getString(hidden ? "hotbar.visibility.hidden-material"
                        : "hotbar.visibility.shown-material"), hidden ? Material.GRAY_DYE : Material.LIME_DYE),
                config.getString(hidden ? "hotbar.visibility.hidden-name" : "hotbar.visibility.shown-name",
                        "Players"));
    }

    private void put(Player player, String id, int slot, Material material, String name) {
        player.getInventory().setItem(slot, tagged(id, material, name));
    }

    private ItemStack tagged(String id, Material material, String name) {
        ItemStack item = Menu.item(material, Text.item(name), List.of(), false);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, id);
        item.setItemMeta(meta);
        return item;
    }

    public String id(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    @EventHandler
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() == Action.PHYSICAL) {
            return;
        }
        String id = id(event.getItem());
        if (id == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        switch (id) {
            case "games" -> new GameMenu(plugin).open(player);
            case "lobbies" -> new LobbyMenu(plugin).open(player);
            case "profile" -> new ProfileMenu(plugin, player).open(player);
            case "visibility" -> {
                if (!plugin.visibility().toggle(player)) {
                    plugin.message(player, "wait", Map.of());
                    return;
                }
                plugin.message(player, plugin.visibility().hides(player) ? "players-hidden" : "players-shown",
                        Map.of());
                updateVisibility(player);
            }
            default -> {
            }
        }
    }

    static Material material(String name, Material fallback) {
        if (name == null) {
            return fallback;
        }
        Material material = Material.matchMaterial(name);
        return material == null || !material.isItem() ? fallback : material;
    }
}
