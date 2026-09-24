package dev.sirius.cloud.plugin.permissions;

import com.google.gson.JsonObject;
import dev.sirius.cloud.api.permission.PermissionGroup;
import dev.sirius.cloud.api.permission.PermissionResolver;
import dev.sirius.cloud.api.permission.PermissionSnapshot;
import dev.sirius.cloud.api.permission.PermissionUser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * {@code /perms} - the whole management surface.
 *
 * <p>Nothing is changed locally. Every edit is a request to the node, which
 * owns the data and republishes it to every server, so running the command on
 * one server and seeing the result on another is the normal case rather than a
 * feature.
 */
final class PermsCommand implements CommandExecutor, TabCompleter {

    private static final String MANAGE = "siriuscloud.perms.manage";
    private static final String VIEW = "siriuscloud.perms.view";

    private final PermissionsPlugin plugin;

    PermsCommand(PermissionsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String[] args) {
        PermissionSnapshot snapshot = plugin.snapshot();
        if (snapshot == null) {
            send(sender, NamedTextColor.RED,
                    "The cloud has not sent its permission data yet. Try again in a moment.");
            plugin.requestSnapshot();
            return true;
        }

        if (args.length == 0) {
            usage(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        boolean readOnly = sub.equals("groups") || sub.equals("info") || sub.equals("check");

        if (!sender.hasPermission(readOnly ? VIEW : MANAGE)) {
            send(sender, NamedTextColor.RED, "You do not have permission to do that.");
            return true;
        }

        switch (sub) {
            case "groups" -> groups(sender, snapshot);
            case "info" -> {
                if (args.length < 2) {
                    send(sender, NamedTextColor.RED, "Usage: /" + label + " info <player|group>");
                    return true;
                }
                info(sender, snapshot, args[1]);
            }
            case "check" -> {
                if (args.length < 3) {
                    send(sender, NamedTextColor.RED, "Usage: /" + label + " check <player> <node>");
                    return true;
                }
                check(sender, snapshot, args[1], args[2]);
            }
            case "group" -> group(sender, label, args);
            case "user" -> user(sender, label, args);
            case "reload" -> {
                plugin.requestSnapshot();
                send(sender, NamedTextColor.GREEN, "Asked the cloud for the current permissions.");
            }
            default -> usage(sender, label);
        }
        return true;
    }

    // ------------------------------------------------------------- read only

    private void groups(CommandSender sender, PermissionSnapshot snapshot) {
        if (snapshot.groups().isEmpty()) {
            send(sender, NamedTextColor.GRAY, "No groups are configured.");
            return;
        }
        send(sender, NamedTextColor.AQUA, snapshot.groups().size()
                + " group(s), revision " + snapshot.revision() + ":");
        snapshot.groups().stream()
                .sorted(java.util.Comparator.<PermissionGroup>comparingInt(PermissionGroup::priority).reversed())
                .forEach(group -> send(sender, NamedTextColor.GRAY,
                        "  " + group.name()
                                + "  priority " + group.priority()
                                + "  " + group.permissions().size() + " node(s)"
                                + (group.defaultGroup() ? "  [default]" : "")
                                + (group.inherits().isEmpty() ? "" : "  inherits " + group.inherits())));
    }

    private void info(CommandSender sender, PermissionSnapshot snapshot, String target) {
        Optional<PermissionGroup> group = snapshot.group(target);
        if (group.isPresent()) {
            PermissionGroup found = group.get();
            send(sender, NamedTextColor.AQUA, "Group " + found.name());
            send(sender, NamedTextColor.GRAY, "  Priority : " + found.priority());
            send(sender, NamedTextColor.GRAY, "  Prefix   : '" + found.prefix() + "'");
            send(sender, NamedTextColor.GRAY, "  Suffix   : '" + found.suffix() + "'");
            send(sender, NamedTextColor.GRAY, "  Default  : " + found.defaultGroup());
            send(sender, NamedTextColor.GRAY, "  Inherits : "
                    + (found.inherits().isEmpty() ? "nothing" : String.join(", ", found.inherits())));
            found.permissions().forEach(node -> send(sender,
                    node.startsWith("-") ? NamedTextColor.RED : NamedTextColor.GREEN, "    " + node));
            return;
        }

        OfflinePlayer player = Bukkit.getOfflinePlayerIfCached(target);
        if (player == null) {
            send(sender, NamedTextColor.RED,
                    "No group or known player called '" + target + "'.");
            return;
        }

        Optional<PermissionUser> user = snapshot.user(player.getUniqueId());
        send(sender, NamedTextColor.AQUA, "Player " + target);
        send(sender, NamedTextColor.GRAY, "  UUID   : " + player.getUniqueId());
        send(sender, NamedTextColor.GRAY, "  Groups : " + user.map(value ->
                value.groups().isEmpty() ? "none" : String.join(", ", value.groups()))
                .orElse("none (default only)"));
        user.ifPresent(value -> value.permissions().forEach(node -> send(sender,
                node.startsWith("-") ? NamedTextColor.RED : NamedTextColor.GREEN, "    " + node)));
        send(sender, NamedTextColor.GRAY, "  Prefix : '"
                + PermissionResolver.prefixOf(snapshot, player.getUniqueId()) + "'");
    }

    private void check(CommandSender sender, PermissionSnapshot snapshot, String target, String node) {
        OfflinePlayer player = Bukkit.getOfflinePlayerIfCached(target);
        if (player == null) {
            send(sender, NamedTextColor.RED, "The server has never seen '" + target + "'.");
            return;
        }
        Map<String, Boolean> effective = PermissionResolver.resolve(snapshot, player.getUniqueId());
        boolean has = PermissionResolver.test(effective, node);
        send(sender, has ? NamedTextColor.GREEN : NamedTextColor.RED,
                target + (has ? " has " : " does not have ") + node);
    }

    // ------------------------------------------------------------- mutations

    private void group(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            send(sender, NamedTextColor.RED, "Usage: /" + label + " group <name> "
                    + "<create|delete|set|add|remove|inherit|uninherit> [value]");
            return;
        }
        String name = args[1];
        String action = args[2].toLowerCase(Locale.ROOT);
        JsonObject request = new JsonObject();
        request.addProperty("group", name);

        switch (action) {
            case "create" -> request.addProperty("op", "group-create");
            case "delete" -> request.addProperty("op", "group-delete");
            case "set" -> {
                if (args.length < 5) {
                    send(sender, NamedTextColor.RED, "Usage: /" + label + " group " + name
                            + " set <priority|prefix|suffix|default> <value>");
                    return;
                }
                request.addProperty("op", "group-set");
                request.addProperty("field", args[3].toLowerCase(Locale.ROOT));
                // Everything after the field, so a prefix may contain spaces.
                request.addProperty("value", String.join(" ", List.of(args).subList(4, args.length)));
            }
            case "add", "remove" -> {
                if (args.length < 4) {
                    send(sender, NamedTextColor.RED, "Usage: /" + label + " group " + name
                            + " " + action + " <node>");
                    return;
                }
                request.addProperty("op", "group-perm");
                request.addProperty("action", action.equals("remove") ? "remove" : "add");
                request.addProperty("node", args[3]);
            }
            case "inherit", "uninherit" -> {
                if (args.length < 4) {
                    send(sender, NamedTextColor.RED, "Usage: /" + label + " group " + name
                            + " " + action + " <parent>");
                    return;
                }
                request.addProperty("op", "group-inherit");
                request.addProperty("action", action.equals("uninherit") ? "remove" : "add");
                request.addProperty("parent", args[3]);
            }
            default -> {
                send(sender, NamedTextColor.RED, "Unknown action '" + action + "'.");
                return;
            }
        }
        plugin.mutate(request, reply -> send(sender, NamedTextColor.GREEN, reply));
    }

    private void user(CommandSender sender, String label, String[] args) {
        if (args.length < 4) {
            send(sender, NamedTextColor.RED, "Usage: /" + label
                    + " user <player> <addgroup|removegroup|add|remove> <value>");
            return;
        }
        String target = args[1];
        String action = args[2].toLowerCase(Locale.ROOT);

        JsonObject request = new JsonObject();
        request.addProperty("player", target);

        // The UUID travels whenever the server knows it, because names are
        // reusable and a permission bound to a name is a permission handed to
        // whoever claims it next.
        OfflinePlayer player = Bukkit.getOfflinePlayerIfCached(target);
        if (player != null) {
            request.addProperty("uuid", player.getUniqueId().toString());
        }

        switch (action) {
            case "addgroup", "removegroup" -> {
                request.addProperty("op", "user-group");
                request.addProperty("action", action.equals("removegroup") ? "remove" : "add");
                request.addProperty("group", args[3]);
            }
            case "add", "remove" -> {
                request.addProperty("op", "user-perm");
                request.addProperty("action", action.equals("remove") ? "remove" : "add");
                request.addProperty("node", args[3]);
            }
            default -> {
                send(sender, NamedTextColor.RED, "Unknown action '" + action + "'.");
                return;
            }
        }
        plugin.mutate(request, reply -> send(sender, NamedTextColor.GREEN, reply));
    }

    private void usage(CommandSender sender, String label) {
        send(sender, NamedTextColor.AQUA, "/" + label + " commands:");
        for (String line : new String[]{
                "groups                              every group",
                "info <player|group>                 details of either",
                "check <player> <node>               test one permission",
                "group <name> create|delete",
                "group <name> set <field> <value>    priority, prefix, suffix, default",
                "group <name> add|remove <node>",
                "group <name> inherit|uninherit <g>",
                "user <player> addgroup|removegroup <group>",
                "user <player> add|remove <node>",
                "reload                              re-fetch from the cloud"}) {
            send(sender, NamedTextColor.GRAY, "  " + line);
        }
    }

    private static void send(CommandSender sender, NamedTextColor colour, String text) {
        sender.sendMessage(Component.text(text, colour));
    }

    // ------------------------------------------------------------ completion

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, String[] args) {
        PermissionSnapshot snapshot = plugin.snapshot();
        if (snapshot == null) {
            return List.of();
        }

        if (args.length <= 1) {
            return filter(List.of("groups", "info", "check", "group", "user", "reload"),
                    args.length == 0 ? "" : args[0]);
        }

        List<String> groupNames = snapshot.groups().stream().map(PermissionGroup::name).toList();

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "group" -> args.length == 2
                    ? filter(groupNames, args[1])
                    : args.length == 3
                            ? filter(List.of("create", "delete", "set", "add",
                                    "remove", "inherit", "uninherit"), args[2])
                            : args.length == 4 && args[2].equalsIgnoreCase("set")
                                    ? filter(List.of("priority", "prefix", "suffix", "default"), args[3])
                                    : args.length == 4 && args[2].toLowerCase(Locale.ROOT)
                                            .endsWith("inherit")
                                            ? filter(groupNames, args[3])
                                            : List.of();
            case "user" -> args.length == 2
                    ? filter(onlineNames(), args[1])
                    : args.length == 3
                            ? filter(List.of("addgroup", "removegroup", "add", "remove"), args[2])
                            : args.length == 4 && args[2].toLowerCase(Locale.ROOT).endsWith("group")
                                    ? filter(groupNames, args[3])
                                    : List.of();
            case "info" -> {
                List<String> both = new ArrayList<>(groupNames);
                both.addAll(onlineNames());
                yield filter(both, args[1]);
            }
            case "check" -> args.length == 2 ? filter(onlineNames(), args[1]) : List.of();
            default -> List.of();
        };
    }

    private static List<String> onlineNames() {
        return Bukkit.getOnlinePlayers().stream()
                .map(org.bukkit.entity.Player::getName)
                .map(name -> (String) name)
                .toList();
    }

    private static List<String> filter(List<String> candidates, String partial) {
        String prefix = partial.toLowerCase(Locale.ROOT);
        return candidates.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
    }
}
