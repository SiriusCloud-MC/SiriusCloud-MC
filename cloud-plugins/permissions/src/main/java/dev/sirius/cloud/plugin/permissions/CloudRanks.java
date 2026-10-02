package dev.sirius.cloud.plugin.permissions;

import dev.sirius.cloud.api.permission.PermissionGroup;
import dev.sirius.cloud.api.permission.PermissionResolver;
import dev.sirius.cloud.api.permission.PermissionSnapshot;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.function.Supplier;

/** Ranks from the cloud's own permission groups: the highest-priority group a player is in. */
final class CloudRanks implements RankSource {

    private final Supplier<PermissionSnapshot> snapshot;

    CloudRanks(Supplier<PermissionSnapshot> snapshot) {
        this.snapshot = snapshot;
    }

    @Override
    public Rank rank(Player player) {
        PermissionSnapshot current = snapshot.get();
        if (current == null) {
            return Rank.NONE;
        }
        Optional<PermissionGroup> group = PermissionResolver.highest(current, player.getUniqueId());
        return group.map(found -> new Rank(found.name(), found.priority(), found.prefix(), found.suffix()))
                .orElse(Rank.NONE);
    }
}
