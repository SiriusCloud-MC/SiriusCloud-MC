package dev.sirius.cloud.api.permission;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Turns groups, inheritance and a player's own nodes into a flat answer.
 *
 * <p>Pure and side-effect free, and deliberately shared by the node module and
 * every plugin. Two implementations of "does this player have that?" would
 * eventually disagree, and a permissions system that answers differently
 * depending on which server you ask is worse than no permissions system.
 *
 * <p>Precedence, weakest first:
 *
 * <ol>
 *   <li>the default group and anything it inherits,
 *   <li>the player's groups in ascending priority, so the highest wins,
 *   <li>the player's own nodes.
 * </ol>
 *
 * A node prefixed with {@code -} denies, and a later rule beats an earlier one.
 */
public final class PermissionResolver {

    private PermissionResolver() {
    }

    /**
     * Every node that applies to a player, in precedence order.
     *
     * @return node to granted/denied, insertion-ordered weakest rule first
     */
    public static Map<String, Boolean> resolve(PermissionSnapshot snapshot, UUID player) {
        Map<String, Boolean> effective = new LinkedHashMap<>();

        snapshot.defaultGroup().ifPresent(group -> applyGroup(snapshot, group, effective, new HashSet<>()));

        Optional<PermissionUser> user = snapshot.user(player);
        if (user.isEmpty()) {
            return effective;
        }

        // Ascending priority: the highest-priority group is applied last and
        // therefore overrides the others where they conflict.
        List<PermissionGroup> groups = new ArrayList<>();
        for (String name : user.get().groups()) {
            snapshot.group(name).ifPresent(groups::add);
        }
        groups.sort(java.util.Comparator.comparingInt(PermissionGroup::priority));
        groups.forEach(group -> applyGroup(snapshot, group, effective, new HashSet<>()));

        // The player's own nodes are the last word.
        user.get().permissions().forEach(node -> apply(effective, node));

        return effective;
    }

    /** The prefix of a player's highest-priority group, or empty. */
    public static String prefixOf(PermissionSnapshot snapshot, UUID player) {
        return highest(snapshot, player).map(PermissionGroup::prefix).orElse("");
    }

    /** The suffix of a player's highest-priority group, or empty. */
    public static String suffixOf(PermissionSnapshot snapshot, UUID player) {
        return highest(snapshot, player).map(PermissionGroup::suffix).orElse("");
    }

    /**
     * The group whose prefix and suffix a player wears.
     *
     * <p>Unlike permissions, which merge, exactly one group has to win here:
     * a player cannot display two prefixes at once.
     */
    public static Optional<PermissionGroup> highest(PermissionSnapshot snapshot, UUID player) {
        List<PermissionGroup> candidates = new ArrayList<>();
        snapshot.user(player).ifPresent(user ->
                user.groups().forEach(name -> snapshot.group(name).ifPresent(candidates::add)));
        if (candidates.isEmpty()) {
            snapshot.defaultGroup().ifPresent(candidates::add);
        }
        return candidates.stream().max(java.util.Comparator.comparingInt(PermissionGroup::priority));
    }

    /**
     * Whether a resolved map grants a node, honouring wildcards.
     *
     * <p>Checked most specific first: an exact rule beats {@code a.b.*}, which
     * beats {@code a.*}, which beats {@code *}. Without that ordering a broad
     * grant would silently outrank the narrow denial written to carve an
     * exception out of it.
     */
    public static boolean test(Map<String, Boolean> effective, String node) {
        String target = node.toLowerCase(Locale.ROOT);

        Boolean exact = effective.get(target);
        if (exact != null) {
            return exact;
        }

        for (int cut = target.lastIndexOf('.'); cut >= 0; cut = target.lastIndexOf('.', cut - 1)) {
            Boolean wildcard = effective.get(target.substring(0, cut + 1) + "*");
            if (wildcard != null) {
                return wildcard;
            }
        }

        Boolean root = effective.get("*");
        return root != null && root;
    }

    private static void applyGroup(PermissionSnapshot snapshot, PermissionGroup group,
                                   Map<String, Boolean> effective, Set<String> visiting) {
        // Inheritance is user-editable, so a cycle is a configuration mistake
        // rather than an impossibility. Tracking the path turns it into a
        // no-op instead of a stack overflow that takes the server with it.
        if (!visiting.add(group.name().toLowerCase(Locale.ROOT))) {
            return;
        }

        for (String parent : group.inherits()) {
            snapshot.group(parent).ifPresent(inherited ->
                    applyGroup(snapshot, inherited, effective, visiting));
        }
        group.permissions().forEach(node -> apply(effective, node));
    }

    private static void apply(Map<String, Boolean> effective, String node) {
        String value = node.trim();
        if (value.isEmpty()) {
            return;
        }
        boolean granted = !value.startsWith("-");
        String name = (granted ? value : value.substring(1)).toLowerCase(Locale.ROOT);
        if (!name.isEmpty()) {
            // Re-put so the last rule also becomes the last entry, which keeps
            // the map's order a readable record of how it was derived.
            effective.remove(name);
            effective.put(name, granted);
        }
    }
}
