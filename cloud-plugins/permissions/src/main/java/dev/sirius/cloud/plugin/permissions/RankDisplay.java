package dev.sirius.cloud.plugin.permissions;

import dev.sirius.cloud.api.permission.PermissionDisplay;
import dev.sirius.cloud.api.permission.PermissionGroup;
import dev.sirius.cloud.api.permission.PermissionResolver;
import dev.sirius.cloud.api.permission.PermissionSnapshot;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyFormat;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Shows each player's rank: in chat, above their head and in the tab list.
 *
 * <p>Nametags are scoreboard teams on the main scoreboard, one per group,
 * named so that vanilla's tab ordering - by team name - puts the highest
 * priority first. Every player with a group is in a team, including the
 * default one, because players without a team sort above everyone else.
 *
 * <p>A plugin that gives players a scoreboard of its own (a sidebar plugin,
 * say) replaces the main one for them, and with it these teams. That is how
 * scoreboards work rather than something this class can route around; such
 * plugins usually offer to copy the main scoreboard's teams.
 */
final class RankDisplay implements Listener {

    /** Only teams with this prefix are ours to create, change and remove. */
    private static final String TEAM_PREFIX = "sc_";

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final Supplier<PermissionSnapshot> snapshot;

    RankDisplay(Supplier<PermissionSnapshot> snapshot) {
        this.snapshot = snapshot;
    }

    /** Main thread only: teams and tab names are server state. */
    void apply(Player player, PermissionSnapshot current) {
        PermissionDisplay display = current.display();
        Optional<PermissionGroup> group = PermissionResolver.highest(current, player.getUniqueId());
        String prefix = group.map(PermissionGroup::prefix).orElse("");
        String suffix = group.map(PermissionGroup::suffix).orElse("");
        boolean decorated = !prefix.isEmpty() || !suffix.isEmpty();

        // Colour codes carry on, so the name takes the prefix's last colour.
        Component name = LEGACY.deserialize(prefix + player.getName() + suffix);
        player.displayName(decorated ? name : null);
        player.playerListName(display.tablist() && decorated ? name : null);

        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        Team existing = board.getEntryTeam(player.getName());
        if (!display.nametags() || group.isEmpty()) {
            if (existing != null && existing.getName().startsWith(TEAM_PREFIX)) {
                existing.removeEntry(player.getName());
            }
            return;
        }

        Team team = team(board, group.get());
        team.prefix(LEGACY.deserialize(prefix));
        team.suffix(LEGACY.deserialize(suffix));
        team.color(lastColor(prefix).orElse(NamedTextColor.WHITE));
        if (!team.hasEntry(player.getName())) {
            team.addEntry(player.getName());
        }
    }

    /** Main thread only. Takes the player out of our team, which the main scoreboard would otherwise save. */
    void forget(Player player) {
        Team team = Bukkit.getScoreboardManager().getMainScoreboard().getEntryTeam(player.getName());
        if (team != null && team.getName().startsWith(TEAM_PREFIX)) {
            team.removeEntry(player.getName());
        }
    }

    /** Main thread only. Drops teams nobody is in, such as those of a renamed or deleted group. */
    void pruneEmptyTeams() {
        for (Team team : Bukkit.getScoreboardManager().getMainScoreboard().getTeams()) {
            if (team.getName().startsWith(TEAM_PREFIX) && team.getEntries().isEmpty()) {
                team.unregister();
            }
        }
    }

    /** Main thread only. Every team of ours, whether or not anyone is in it. */
    void removeAllTeams() {
        for (Team team : Bukkit.getScoreboardManager().getMainScoreboard().getTeams()) {
            if (team.getName().startsWith(TEAM_PREFIX)) {
                team.unregister();
            }
        }
    }

    /**
     * Formats chat as the node's chat format says.
     *
     * <p>Runs after the core plugin's mute check, which cancels at LOWEST, and
     * leaves chat alone entirely when the format is switched off, so a chat
     * plugin can own it instead.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        PermissionSnapshot current = snapshot.get();
        if (current == null || !current.display().chat()) {
            return;
        }
        Optional<PermissionGroup> group = PermissionResolver.highest(current, event.getPlayer().getUniqueId());
        String format = current.display().chatFormat()
                .replace("{prefix}", group.map(PermissionGroup::prefix).orElse(""))
                .replace("{suffix}", group.map(PermissionGroup::suffix).orElse(""))
                .replace("{name}", event.getPlayer().getName());

        int split = format.indexOf("{message}");
        String head = format.substring(0, split);
        Component before = LEGACY.deserialize(head);
        Component after = LEGACY.deserialize(format.substring(split + "{message}".length()));
        Optional<NamedTextColor> messageColor = lastColor(head);

        event.renderer(ChatRenderer.viewerUnaware((source, displayName, message) -> {
            // The message is the player's own text, never parsed for colour
            // codes, so nobody can colour their chat by typing '&c'.
            Component body = messageColor.map(message::colorIfAbsent).orElse(message);
            return Component.text().append(before).append(body).append(after).build();
        }));
    }

    private static Team team(Scoreboard board, PermissionGroup group) {
        // Highest priority sorts first, and team names are what tab sorts by.
        int rank = 999 - Math.max(0, Math.min(999, group.priority()));
        String key = group.name().toLowerCase(Locale.ROOT);
        String name = TEAM_PREFIX + String.format(Locale.ROOT, "%03d", rank)
                + key.substring(0, Math.min(key.length(), 10));
        Team team = board.getTeam(name);
        return team != null ? team : board.registerNewTeam(name);
    }

    /** The colour in effect at the end of a legacy string: what text after it would be. */
    static Optional<NamedTextColor> lastColor(String legacy) {
        NamedTextColor found = null;
        for (int index = 0; index + 1 < legacy.length(); index++) {
            char marker = legacy.charAt(index);
            if (marker != '&' && marker != LegacyComponentSerializer.SECTION_CHAR) {
                continue;
            }
            LegacyFormat format = LegacyComponentSerializer.parseChar(legacy.charAt(index + 1));
            if (format == null) {
                continue;
            }
            if (format.reset()) {
                found = null;
            }
            TextColor color = format.color();
            if (color != null) {
                found = NamedTextColor.nearestTo(color);
            }
        }
        return Optional.ofNullable(found);
    }
}
