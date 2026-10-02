package dev.sirius.cloud.plugin.permissions;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.event.group.GroupDataRecalculateEvent;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Ranks from LuckPerms: the player's prefix and suffix as LuckPerms resolves
 * them, sorted by the weight of their primary group.
 *
 * <p>Only loaded when LuckPerms is installed; nothing else in the plugin
 * touches a LuckPerms class, so it runs fine without it.
 */
final class LuckPermsRanks implements RankSource {

    private final LuckPerms luckPerms = LuckPermsProvider.get();

    /**
     * @param changed called on the main thread for a player whose rank may have
     *                changed, or with null when it may have changed for everyone
     */
    LuckPermsRanks(Plugin plugin, Consumer<Player> changed) {
        // A rank given or taken, a prefix edited: LuckPerms recalculates the
        // user, and the name above their head should follow at once.
        luckPerms.getEventBus().subscribe(plugin, UserDataRecalculateEvent.class, event -> {
            UUID id = event.getUser().getUniqueId();
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = Bukkit.getPlayer(id);
                if (player != null) {
                    changed.accept(player);
                }
            });
        });
        // A group's prefix or weight changed: everyone in it is affected.
        luckPerms.getEventBus().subscribe(plugin, GroupDataRecalculateEvent.class,
                event -> Bukkit.getScheduler().runTask(plugin, () -> changed.accept(null)));
    }

    @Override
    public Rank rank(Player player) {
        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            return Rank.NONE;
        }
        CachedMetaData meta = user.getCachedData().getMetaData();
        String primary = user.getPrimaryGroup();
        Group group = luckPerms.getGroupManager().getGroup(primary);
        int weight = group == null ? 0 : group.getWeight().orElse(0);
        return new Rank(primary, weight, nonNull(meta.getPrefix()), nonNull(meta.getSuffix()));
    }

    private static String nonNull(String text) {
        return text == null ? "" : text;
    }
}
