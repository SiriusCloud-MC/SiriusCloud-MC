package dev.sirius.cloud.plugin.permissions;

import org.bukkit.entity.Player;

/** Where a player's rank - prefix, suffix and how high it sorts - comes from. */
interface RankSource {

    /**
     * @param key    the rank's name, e.g. its group; null when the player has none
     * @param weight how high it sorts: higher is shown first in the tab list
     */
    record Rank(String key, int weight, String prefix, String suffix) {

        static final Rank NONE = new Rank(null, 0, "", "");

        boolean decorated() {
            return !prefix.isEmpty() || !suffix.isEmpty();
        }
    }

    Rank rank(Player player);
}
