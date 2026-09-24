package dev.sirius.cloud.plugin.permissions;

import dev.sirius.cloud.api.permission.PermissionResolver;
import dev.sirius.cloud.api.permission.PermissionSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Pushes resolved permissions onto Bukkit players.
 *
 * <p>One {@link PermissionAttachment} per player, replaced wholesale whenever
 * the snapshot changes. Replacing rather than diffing is the point: a node
 * removed from a group has to stop applying, and working out which of a
 * player's attached nodes came from which rule is exactly the bookkeeping that
 * goes wrong.
 */
final class PermissionApplier {

    private final Plugin plugin;

    /** Live attachments, so the previous one can be removed before reattaching. */
    private final Map<UUID, PermissionAttachment> attachments = new HashMap<>();

    PermissionApplier(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Reapplies to everyone currently online. Called when a snapshot arrives. */
    void applyAll(PermissionSnapshot snapshot) {
        // Attachments touch Bukkit's permissible state, which is not safe to
        // mutate off the main thread. Snapshots arrive on a Netty thread.
        Bukkit.getScheduler().runTask(plugin,
                () -> Bukkit.getOnlinePlayers().forEach(player -> apply(player, snapshot)));
    }

    void apply(Player player, PermissionSnapshot snapshot) {
        PermissionAttachment previous = attachments.remove(player.getUniqueId());
        if (previous != null) {
            try {
                player.removeAttachment(previous);
            } catch (IllegalArgumentException exception) {
                // Already gone, because the player relogged between snapshots.
            }
        }

        PermissionAttachment attachment = player.addAttachment(plugin);
        Map<String, Boolean> effective = PermissionResolver.resolve(snapshot, player.getUniqueId());

        effective.forEach((node, granted) -> {
            if (node.endsWith("*")) {
                expandWildcard(attachment, node, granted);
            }
            // Set literally too: a node declared in a plugin.yml with children
            // is expanded by Bukkit itself, and this is what lets that work.
            attachment.setPermission(node, granted);
        });

        attachments.put(player.getUniqueId(), attachment);
        player.recalculatePermissions();
    }

    void forget(UUID player) {
        attachments.remove(player);
    }

    void clear() {
        attachments.clear();
    }

    /**
     * Applies {@code some.thing.*} to every permission the server knows about.
     *
     * <p>Bukkit does not expand wildcards on its own; it only understands the
     * parent/child relationships plugins declare. Without this, granting
     * {@code essentials.*} would grant precisely nothing, which is not what
     * anybody means by it. Only registered permissions can be matched, so a
     * plugin that never declares its nodes still will not be covered - that is
     * a limit of the platform rather than of this code.
     */
    private static void expandWildcard(PermissionAttachment attachment, String node, boolean granted) {
        String prefix = node.substring(0, node.length() - 1).toLowerCase(Locale.ROOT);

        for (Permission permission : Bukkit.getPluginManager().getPermissions()) {
            String name = permission.getName().toLowerCase(Locale.ROOT);
            if (prefix.isEmpty() || name.startsWith(prefix)) {
                attachment.setPermission(permission, granted);
            }
        }
    }
}
