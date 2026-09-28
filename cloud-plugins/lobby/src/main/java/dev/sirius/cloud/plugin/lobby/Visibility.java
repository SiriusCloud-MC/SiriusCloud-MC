package dev.sirius.cloud.plugin.lobby;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Players who chose not to see the others in the lobby. */
public final class Visibility {

    private static final long COOLDOWN_MILLIS = 2_000;

    private final LobbyPlugin plugin;
    private final Set<UUID> hiding = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastToggle = new ConcurrentHashMap<>();

    public Visibility(LobbyPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean hides(Player player) {
        return hiding.contains(player.getUniqueId());
    }

    /** Returns false if asked again too soon; hiding and showing everyone is not free. */
    public boolean toggle(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastToggle.get(player.getUniqueId());
        if (last != null && now - last < COOLDOWN_MILLIS) {
            return false;
        }
        lastToggle.put(player.getUniqueId(), now);
        boolean hide = hiding.add(player.getUniqueId());
        if (!hide) {
            hiding.remove(player.getUniqueId());
        }
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other != player) {
                if (hide) {
                    player.hidePlayer(plugin, other);
                } else {
                    player.showPlayer(plugin, other);
                }
            }
        }
        return true;
    }

    /** Someone joined: hidden from everyone who hides players. */
    public void joined(Player player) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other != player && hiding.contains(other.getUniqueId())) {
                other.hidePlayer(plugin, player);
            }
        }
    }

    public void forget(Player player) {
        hiding.remove(player.getUniqueId());
        lastToggle.remove(player.getUniqueId());
    }
}
