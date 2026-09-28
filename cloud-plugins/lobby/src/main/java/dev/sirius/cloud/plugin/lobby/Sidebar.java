package dev.sirius.cloud.plugin.lobby;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The sidebar on the right of the screen.
 *
 * <p>Each player gets a scoreboard of their own, since each sees their own
 * rank. That would normally hide the rank prefixes the permissions plugin
 * puts above heads, because those live in teams on the main scoreboard; so
 * every team there is copied into each player's board on every update.
 */
public final class Sidebar {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final LobbyPlugin plugin;
    private final Map<UUID, Scoreboard> boards = new HashMap<>();

    public Sidebar(LobbyPlugin plugin) {
        this.plugin = plugin;
    }

    public void show(Player player) {
        if (!plugin.getConfig().getBoolean("sidebar.enabled", true)) {
            return;
        }
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        boards.put(player.getUniqueId(), board);
        player.setScoreboard(board);
        update(player, board);
    }

    public void remove(Player player) {
        boards.remove(player.getUniqueId());
    }

    public void hideAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (boards.remove(player.getUniqueId()) != null) {
                player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            }
        }
    }

    /** Every couple of seconds, on the main thread. */
    public void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Scoreboard board = boards.get(player.getUniqueId());
            if (board != null) {
                update(player, board);
            }
        }
    }

    private void update(Player player, Scoreboard board) {
        copyTeams(board);

        Objective objective = board.getObjective("lobby");
        if (objective == null) {
            objective = board.registerNewObjective("lobby", Criteria.DUMMY, Component.empty());
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            objective.numberFormat(NumberFormat.blank());
        }
        objective.displayName(Text.render(plugin.getConfig().getString("sidebar.title", "SiriusCloud")));

        List<String> lines = plugin.getConfig().getStringList("sidebar.lines");
        Map<String, Object> values = Map.of(
                "player", player.getName(),
                "rank", rank(player),
                "lobby", plugin.lobbyName(),
                "online", plugin.network().online(),
                "lobby-players", Bukkit.getOnlinePlayers().size(),
                "date", LocalDate.now().format(DATE));

        Set<String> used = new HashSet<>();
        for (int index = 0; index < lines.size() && index < 15; index++) {
            // Invisible, unique entries; what shows is each score's custom name.
            String entry = "§" + Integer.toHexString(index) + "§r";
            used.add(entry);
            var score = objective.getScore(entry);
            score.setScore(lines.size() - index);
            score.customName(Text.render(lines.get(index), values));
        }
        for (String entry : board.getEntries()) {
            if (!used.contains(entry) && entry.startsWith("§") && entry.endsWith("§r")) {
                board.resetScores(entry);
            }
        }
    }

    /** The prefix of the player's rank team, or the configured stand-in. */
    public Component rank(Player player) {
        Team team = Bukkit.getScoreboardManager().getMainScoreboard().getEntryTeam(player.getName());
        if (team != null) {
            Component prefix = team.prefix();
            String plain = PlainTextComponentSerializer.plainText().serialize(prefix).trim();
            if (!plain.isEmpty()) {
                // "Admin | " reads as "Admin" on its own line.
                String trimmed = plain.endsWith("|") ? plain.substring(0, plain.length() - 1).trim() : plain;
                net.kyori.adventure.text.format.TextColor color = team.hasColor() ? team.color() : null;
                return Component.text(trimmed).color(color);
            }
        }
        return Text.render(plugin.getConfig().getString("sidebar.no-rank", "<gray>Player"));
    }

    /** Mirrors the main scoreboard's teams, which carry the nametag prefixes and the tab order. */
    private static void copyTeams(Scoreboard board) {
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        Set<String> names = new HashSet<>();
        for (Team source : main.getTeams()) {
            names.add(source.getName());
            Team target = board.getTeam(source.getName());
            if (target == null) {
                target = board.registerNewTeam(source.getName());
            }
            target.prefix(source.prefix());
            target.suffix(source.suffix());
            if (source.hasColor()) {
                target.color(net.kyori.adventure.text.format.NamedTextColor.nearestTo(source.color()));
            }
            for (Team.Option option : Team.Option.values()) {
                target.setOption(option, source.getOption(option));
            }
            Set<String> wanted = source.getEntries();
            for (String entry : target.getEntries()) {
                if (!wanted.contains(entry)) {
                    target.removeEntry(entry);
                }
            }
            for (String entry : wanted) {
                if (!target.hasEntry(entry)) {
                    target.addEntry(entry);
                }
            }
        }
        for (Team team : board.getTeams()) {
            if (!names.contains(team.getName())) {
                team.unregister();
            }
        }
    }
}
