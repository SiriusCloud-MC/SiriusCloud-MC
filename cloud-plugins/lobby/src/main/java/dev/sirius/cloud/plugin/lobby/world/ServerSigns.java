package dev.sirius.cloud.plugin.lobby.world;

import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.api.service.ServiceProperties;
import dev.sirius.cloud.api.service.ServiceState;
import dev.sirius.cloud.plugin.lobby.LobbyPlugin;
import dev.sirius.cloud.plugin.lobby.NetworkView;
import dev.sirius.cloud.plugin.lobby.Text;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Signs that each show one server of a game, live: its name, how full it is,
 * whether it is waiting for players or in a round. A row of them is a sign
 * wall - one sign per server, filled in order as servers start.
 *
 * <p>Write {@code [cloud]} on the first line and a game group on the second
 * to make one; sneak and break it to remove it. Either needs
 * {@code siriuscloud.lobby.admin}. Signs are stored in the cloud's database,
 * so every lobby server shows them, see {@link Placements}.
 */
public final class ServerSigns implements Listener {

    private final LobbyPlugin plugin;
    /** Our signs on this server, by their block's position. Main thread only. */
    private final Map<String, Placements.SignRecord> signs = new HashMap<>();
    /** Which server each sign shows right now, for clicks. Main thread only. */
    private final Map<String, String> showing = new HashMap<>();

    public ServerSigns(LobbyPlugin plugin) {
        this.plugin = plugin;
    }

    private static String key(String world, int x, int y, int z) {
        return world + "," + x + "," + y + "," + z;
    }

    private static String key(Block block) {
        return key(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    // ---------------------------------------------------------------- loading

    /** Fetches the stored signs and builds any this world is missing. */
    public void reload() {
        Placements.loadSigns(plugin.lobbyGroup()).thenAccept(stored ->
                Bukkit.getScheduler().runTask(plugin, () -> apply(stored)));
    }

    private void apply(Map<String, Placements.SignRecord> stored) {
        Map<String, Placements.SignRecord> next = new HashMap<>();
        for (Placements.SignRecord sign : stored.values()) {
            World world = Bukkit.getWorld(sign.world());
            if (world == null) {
                continue;
            }
            Block block = world.getBlockAt(sign.x(), sign.y(), sign.z());
            if (!(block.getState() instanceof Sign)) {
                // Placed on another lobby server: build it here too.
                try {
                    block.setBlockData(Bukkit.createBlockData(sign.block()), false);
                } catch (IllegalArgumentException unknownBlock) {
                    continue;
                }
            }
            next.put(key(sign.world(), sign.x(), sign.y(), sign.z()), sign);
        }
        // Removed on another server: take ours down as well.
        for (Map.Entry<String, Placements.SignRecord> old : signs.entrySet()) {
            if (!next.containsKey(old.getKey())) {
                World world = Bukkit.getWorld(old.getValue().world());
                if (world != null) {
                    Block block = world.getBlockAt(old.getValue().x(), old.getValue().y(), old.getValue().z());
                    if (block.getState() instanceof Sign) {
                        block.setType(org.bukkit.Material.AIR, false);
                    }
                }
            }
        }
        signs.clear();
        signs.putAll(next);
        render();
    }

    // -------------------------------------------------------------- rendering

    /** Every couple of seconds, on the main thread. */
    public void render() {
        showing.clear();
        Map<String, List<Placements.SignRecord>> byGame = new HashMap<>();
        for (Placements.SignRecord sign : signs.values()) {
            byGame.computeIfAbsent(sign.game().toLowerCase(Locale.ROOT), key -> new ArrayList<>()).add(sign);
        }
        for (List<Placements.SignRecord> wall : byGame.values()) {
            // In a fixed order, so a server keeps its sign while it runs.
            // Top row first, then along it.
            wall.sort(Comparator.comparing(Placements.SignRecord::world)
                    .thenComparing(Comparator.comparingInt(Placements.SignRecord::y).reversed())
                    .thenComparingInt(Placements.SignRecord::x).thenComparingInt(Placements.SignRecord::z));
            String game = wall.getFirst().game();
            NetworkView.Game status = plugin.network().game(game);
            List<ServiceInfo> servers = plugin.network().servicesOf(game);
            for (int index = 0; index < wall.size(); index++) {
                Placements.SignRecord sign = wall.get(index);
                ServiceInfo server = index < servers.size() ? servers.get(index) : null;
                draw(sign, game, status, server);
            }
        }
    }

    private void draw(Placements.SignRecord record, String game, NetworkView.Game status, ServiceInfo server) {
        World world = Bukkit.getWorld(record.world());
        if (world == null || !world.isChunkLoaded(record.x() >> 4, record.z() >> 4)) {
            return;
        }
        if (!(world.getBlockAt(record.x(), record.y(), record.z()).getState() instanceof Sign sign)) {
            return;
        }
        List<String> layout;
        Map<String, Object> values = new HashMap<>();
        values.put("game", game);
        if (status.status() == NetworkView.Status.MAINTENANCE) {
            layout = lines("maintenance");
        } else if (server == null) {
            layout = lines("searching");
        } else {
            layout = lines("server");
            values.put("server", server.name());
            values.put("players", server.playerCount());
            values.put("max", server.maxPlayers());
            // From the config, so MiniMessage itself rather than plain text.
            values.put("state", Text.render(state(server)));
            values.put("map", server.property(ServiceProperties.MAP).orElse(""));
            if (server.state() == ServiceState.RUNNING) {
                showing.put(key(record.world(), record.x(), record.y(), record.z()), server.name());
            }
        }
        SignSide front = sign.getSide(Side.FRONT);
        for (int line = 0; line < 4; line++) {
            front.line(line, Text.render(line < layout.size() ? layout.get(line) : "", values));
        }
        sign.setWaxed(true);
        sign.update(false, false);
    }

    private String state(ServiceInfo server) {
        String key;
        if (server.state() != ServiceState.RUNNING) {
            key = "starting";
        } else if (!ServiceProperties.isJoinable(server)) {
            key = "ingame";
        } else if (server.playerCount() >= server.maxPlayers()) {
            key = "full";
        } else {
            key = "lobby";
        }
        return plugin.getConfig().getString("signs.states." + key, key);
    }

    private List<String> lines(String layout) {
        List<String> lines = plugin.getConfig().getStringList("signs." + layout);
        return lines.isEmpty() ? List.of("<dark_gray>[<aqua>{game}<dark_gray>]") : lines;
    }

    // ---------------------------------------------------------------- players

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onWrite(SignChangeEvent event) {
        String first = plain(event, 0);
        if (!first.equalsIgnoreCase("[cloud]")) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission("siriuscloud.lobby.admin")) {
            return;
        }
        String game = plain(event, 1).trim();
        var group = plugin.network().group(game);
        if (group.isEmpty()) {
            player.sendMessage(Text.render("<red>There is no group called <white>{game}<red>.", Map.of("game", game)));
            return;
        }
        Block block = event.getBlock();
        Placements.SignRecord record = new Placements.SignRecord(UUID.randomUUID().toString(), plugin.lobbyGroup(),
                block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                block.getBlockData().getAsString(), group.get().name());
        signs.put(key(block), record);
        Placements.save(record);
        player.sendMessage(Text.render("<green>Server sign for <white>{game}<green> created. Every lobby server"
                + " shows it.", Map.of("game", group.get().name())));
        Bukkit.getScheduler().runTask(plugin, this::render);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
                || event.getClickedBlock() == null) {
            return;
        }
        String key = key(event.getClickedBlock());
        Placements.SignRecord record = signs.get(key);
        if (record == null) {
            return;
        }
        event.setCancelled(true);
        event.setUseItemInHand(Event.Result.DENY);
        String server = showing.get(key);
        if (server == null) {
            plugin.message(event.getPlayer(), "offline", Map.of("game", record.game()));
            return;
        }
        ServiceInfo info = plugin.network().servicesOf(record.game()).stream()
                .filter(service -> service.name().equals(server)).findFirst().orElse(null);
        if (info == null || !ServiceProperties.isJoinable(info) || info.playerCount() >= info.maxPlayers()) {
            plugin.message(event.getPlayer(), "server-unavailable", Map.of("server", server));
            return;
        }
        plugin.joinServer(event.getPlayer(), server);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent event) {
        String key = key(event.getBlock());
        Placements.SignRecord record = signs.get(key);
        if (record == null) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission("siriuscloud.lobby.admin") || !player.isSneaking()) {
            event.setCancelled(true);
            if (player.hasPermission("siriuscloud.lobby.admin")) {
                player.sendMessage(Text.render("<gray>Sneak while breaking a server sign to remove it."));
            }
            return;
        }
        event.setCancelled(false);
        signs.remove(key);
        Placements.deleteSign(record.id());
        player.sendMessage(Text.render("<yellow>Server sign removed from every lobby server."));
    }

    private static String plain(SignChangeEvent event, int line) {
        var component = event.line(line);
        return component == null ? "" : PlainTextComponentSerializer.plainText().serialize(component);
    }
}
