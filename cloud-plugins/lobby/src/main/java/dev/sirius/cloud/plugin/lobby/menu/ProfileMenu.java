package dev.sirius.cloud.plugin.lobby.menu;

import dev.sirius.cloud.api.driver.CloudDriver;
import dev.sirius.cloud.api.player.PlayerProfile;
import dev.sirius.cloud.plugin.lobby.LobbyPlugin;
import dev.sirius.cloud.plugin.lobby.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A player's history with the network, from the cloud's player profiles:
 * playtime, first and last visit, and the names they have used.
 */
public final class ProfileMenu extends Menu {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")
            .withZone(ZoneId.systemDefault());

    private final LobbyPlugin plugin;
    private final Player player;
    private volatile Optional<PlayerProfile> profile;

    public ProfileMenu(LobbyPlugin plugin, Player player) {
        super(3, Text.render("<dark_gray>Profile"));
        this.plugin = plugin;
        this.player = player;
    }

    /** Opens at once with what is known, and fills in the profile when the node answers. */
    @Override
    public void open(Player viewer) {
        super.open(viewer);
        CloudDriver.instance().players().profile(player.getUniqueId()).thenAccept(loaded -> {
            profile = loaded;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (getInventory().getViewers().contains(viewer)) {
                    render();
                }
            });
        });
    }

    @Override
    public void render() {
        clear();
        ItemStack head = item(Material.PLAYER_HEAD, Text.item("<white>{player}",
                Map.of("player", player.getName())), List.of(
                Text.item("<gray>Rank: {rank}", Map.of("rank", plugin.sidebar().rank(player))),
                Text.item("<gray>Lobby: <white>{lobby}", Map.of("lobby", plugin.lobbyName()))), false);
        if (head.getItemMeta() instanceof SkullMeta skull) {
            skull.setOwningPlayer(player);
            head.setItemMeta(skull);
        }
        set(13, head, null);

        Optional<PlayerProfile> loaded = profile;
        if (loaded == null) {
            set(11, item(Material.CLOCK, Text.item("<gray>Loading..."), List.of(), false), null);
        } else if (loaded.isEmpty()) {
            set(11, item(Material.CLOCK, Text.item("<gray>No history yet"), List.of(), false), null);
        } else {
            PlayerProfile known = loaded.get();
            set(11, item(Material.CLOCK, Text.item("<gold><bold>Playtime"),
                    List.of(Text.item("<white>{time}", Map.of("time", describe(Duration.ofMillis(
                            known.playtimeMillis()))))), false), null);
            set(15, item(Material.WRITABLE_BOOK, Text.item("<aqua><bold>History"), List.of(
                    Text.item("<gray>First joined: <white>{date}", Map.of("date",
                            DATE.format(Instant.ofEpochMilli(known.firstJoin())))),
                    Text.item("<gray>Last seen: <white>{date}", Map.of("date",
                            DATE.format(Instant.ofEpochMilli(known.lastSeen()))))), false), null);
            List<Component> names = new ArrayList<>();
            List<String> history = known.nameHistory();
            for (int index = history.size() - 1; index >= 0 && names.size() < 10; index--) {
                names.add(Text.item("<white>{name}", Map.of("name", history.get(index))));
            }
            set(22, item(Material.NAME_TAG, Text.item("<yellow><bold>Names"), names, false), null);
        }
        frame();
    }

    static String describe(Duration duration) {
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        if (hours >= 24) {
            return hours / 24 + "d " + hours % 24 + "h";
        }
        return hours > 0 ? hours + "h " + minutes + "m" : minutes + "m";
    }
}
