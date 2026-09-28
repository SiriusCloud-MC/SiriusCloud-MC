package dev.sirius.cloud.plugin.lobby.menu;

import com.google.gson.JsonObject;
import dev.sirius.cloud.api.driver.CloudDriver;
import dev.sirius.cloud.api.messaging.MessagingProvider;
import dev.sirius.cloud.plugin.lobby.LobbyPlugin;
import dev.sirius.cloud.plugin.lobby.NetworkView;
import dev.sirius.cloud.plugin.lobby.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Every game on the network, with how many play it and whether it is up.
 *
 * <p>Clicking queues through the node's matchmaking module, which keeps a
 * party together and starts a server when none has room; or, for an entry
 * set to {@code connect}, goes straight to the least busy server.
 */
public final class GameMenu extends Menu {

    /** Where games go when nobody arranged them: the middle of the menu, row by row. */
    private static final int[] AUTO_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31,
            32, 33, 34};
    private static final Material[] AUTO_ICONS = {Material.DIAMOND_SWORD, Material.RED_BED, Material.BOW,
            Material.GRASS_BLOCK, Material.IRON_PICKAXE, Material.TNT, Material.ENDER_PEARL, Material.GOLDEN_APPLE,
            Material.FISHING_ROD, Material.SHIELD};

    private record Entry(String group, int slot, Material icon, String name, List<String> lore, boolean queue) {
    }

    private final LobbyPlugin plugin;

    public GameMenu(LobbyPlugin plugin) {
        super(plugin.getConfig().getInt("games.rows", 3), Text.render(plugin.getConfig().getString("games.title",
                "Games")));
        this.plugin = plugin;
    }

    @Override
    public boolean live() {
        return true;
    }

    @Override
    public void render() {
        clear();
        for (Entry entry : entries()) {
            NetworkView.Game game = plugin.network().game(entry.group());
            List<Component> lore = new ArrayList<>();
            entry.lore().forEach(line -> lore.add(Text.item(line)));
            if (!lore.isEmpty()) {
                lore.add(Component.empty());
            }
            lore.add(Text.item("<gray>Playing: <white>{players}", Map.of("players", game.players())));
            lore.add(Text.item("<gray>Servers: <white>{servers}", Map.of("servers", game.servers())));
            lore.add(Component.empty());
            lore.add(Text.item(switch (game.status()) {
                case OPEN -> "<green>▶ Click to play";
                case STARTING -> "<yellow>▶ Click to play <gray>(a server starts for you)";
                case MAINTENANCE -> "<red>✖ Maintenance";
                case OFFLINE -> "<red>✖ Offline";
            }));
            set(entry.slot(), item(entry.icon(), Text.item(entry.name()), lore,
                    game.status() == NetworkView.Status.OPEN), player -> play(player, entry, game));
        }
        frame();
    }

    private List<Entry> entries() {
        List<Entry> entries = new ArrayList<>();
        ConfigurationSection configured = plugin.getConfig().getConfigurationSection("games.entries");
        if (configured != null && !configured.getKeys(false).isEmpty()) {
            for (String key : configured.getKeys(false)) {
                ConfigurationSection section = configured.getConfigurationSection(key);
                if (section == null) {
                    continue;
                }
                String group = section.getString("group", key);
                entries.add(new Entry(group, section.getInt("slot", entries.size()),
                        material(section.getString("material"), Material.DIAMOND_SWORD),
                        section.getString("name", "<white><bold>" + group), section.getStringList("lore"),
                        !"connect".equalsIgnoreCase(section.getString("action", "queue"))));
            }
            return entries;
        }
        List<String> groups = plugin.network().gameGroups();
        for (int index = 0; index < groups.size() && index < AUTO_SLOTS.length; index++) {
            String group = groups.get(index);
            entries.add(new Entry(group, AUTO_SLOTS[index], AUTO_ICONS[index % AUTO_ICONS.length],
                    "<aqua><bold>" + group, List.of(), true));
        }
        return entries;
    }

    private void play(Player player, Entry entry, NetworkView.Game game) {
        Map<String, Object> values = Map.of("game", entry.group());
        switch (game.status()) {
            case MAINTENANCE -> {
                plugin.message(player, "maintenance", values);
                return;
            }
            case OFFLINE -> {
                plugin.message(player, "offline", values);
                return;
            }
            default -> {
            }
        }
        player.closeInventory();
        CloudDriver driver = CloudDriver.instance();
        if (entry.queue()) {
            JsonObject request = new JsonObject();
            request.addProperty("player", player.getUniqueId().toString());
            request.addProperty("game", entry.group());
            plugin.message(player, "queued", values);
            driver.messaging().publishTo(MessagingProvider.NODE, "matchmaking:queue", request.toString())
                    .exceptionally(error -> failed(player, error));
        } else {
            plugin.message(player, "connecting", Map.of("server", entry.group()));
            driver.players().connectToGroup(player.getUniqueId(), entry.group())
                    .exceptionally(error -> failed(player, error));
        }
    }

    private Void failed(Player player, Throwable error) {
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        plugin.message(player, "failed", Map.of("reason", String.valueOf(cause.getMessage())));
        return null;
    }

    static Material material(String name, Material fallback) {
        if (name == null) {
            return fallback;
        }
        Material material = Material.matchMaterial(name);
        return material == null || !material.isItem() ? fallback : material;
    }
}
