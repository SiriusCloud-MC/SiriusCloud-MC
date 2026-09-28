package dev.sirius.cloud.plugin.lobby.menu;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A chest menu whose items do something when clicked.
 *
 * <p>The menu is the inventory's holder, which is how clicks are told apart
 * from a player's own chests without comparing titles. Menus that show live
 * numbers are redrawn every second while open.
 */
public abstract class Menu implements InventoryHolder {

    private final Inventory inventory;
    private final Map<Integer, Consumer<Player>> actions = new HashMap<>();

    protected Menu(int rows, Component title) {
        this.inventory = Bukkit.createInventory(this, Math.max(1, Math.min(6, rows)) * 9, title);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** Draws the contents. Called on open, and every second for {@link #live()} menus. */
    public abstract void render();

    /** Whether this menu shows numbers that change while it is open. */
    public boolean live() {
        return false;
    }

    public void open(Player player) {
        render();
        player.openInventory(inventory);
    }

    protected void clear() {
        inventory.clear();
        actions.clear();
    }

    protected void set(int slot, ItemStack item, Consumer<Player> action) {
        if (slot < 0 || slot >= inventory.getSize()) {
            return;
        }
        inventory.setItem(slot, item);
        if (action != null) {
            actions.put(slot, action);
        } else {
            actions.remove(slot);
        }
    }

    void click(Player player, int slot) {
        Consumer<Player> action = actions.get(slot);
        if (action != null) {
            action.accept(player);
        }
    }

    /** Fills every empty slot with a dark pane, which reads as a frame. */
    protected void frame() {
        ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, Component.empty(), List.of(), false);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (inventory.getItem(slot) == null) {
                inventory.setItem(slot, pane);
            }
        }
    }

    public static ItemStack item(Material material, Component name, List<Component> lore, boolean glint) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.values());
            if (glint) {
                meta.setEnchantmentGlintOverride(true);
            }
            item.setItemMeta(meta);
        }
        return item;
    }
}
