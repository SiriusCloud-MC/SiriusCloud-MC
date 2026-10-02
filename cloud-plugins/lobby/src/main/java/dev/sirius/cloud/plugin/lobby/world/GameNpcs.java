package dev.sirius.cloud.plugin.lobby.world;

import dev.sirius.cloud.plugin.lobby.LobbyPlugin;
import dev.sirius.cloud.plugin.lobby.NetworkView;
import dev.sirius.cloud.plugin.lobby.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * NPCs that queue players for a game, with a live label above them: the
 * game's name, how many play it, and whether it is open.
 *
 * <p>{@code /cloudnpc create <game> [entity]} puts one where you stand,
 * facing your way. They are stored in the cloud's database and built by
 * every lobby server ({@link Placements}). The entities themselves are never
 * saved with the world: each server spawns its own on start and when their
 * chunk is loaded again, so there are never stale copies left behind.
 */
public final class GameNpcs implements Listener, TabExecutor {

    private final LobbyPlugin plugin;
    private final NamespacedKey key;
    private final Map<String, Placements.NpcRecord> npcs = new HashMap<>();
    /** Spawned entities, by record id. Main thread only. */
    private final Map<String, Entity> bodies = new HashMap<>();
    private final Map<String, TextDisplay> labels = new HashMap<>();

    public GameNpcs(LobbyPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "npc");
    }

    // ---------------------------------------------------------------- loading

    public void reload() {
        Placements.loadNpcs(plugin.lobbyGroup()).thenAccept(stored ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (String id : new ArrayList<>(npcs.keySet())) {
                        if (!stored.containsKey(id)) {
                            despawn(id);
                        }
                    }
                    npcs.clear();
                    npcs.putAll(stored);
                    tick();
                }));
    }

    /** Spawns what is missing and refreshes the labels. Every couple of seconds, main thread. */
    public void tick() {
        for (Placements.NpcRecord npc : npcs.values()) {
            World world = Bukkit.getWorld(npc.world());
            if (world == null || !world.isChunkLoaded((int) Math.floor(npc.x()) >> 4,
                    (int) Math.floor(npc.z()) >> 4)) {
                continue;
            }
            Entity body = bodies.get(npc.id());
            if (body == null || !body.isValid()) {
                spawn(npc, world);
            }
            TextDisplay label = labels.get(npc.id());
            if (label != null && label.isValid()) {
                label.text(label(npc.game()));
            }
        }
    }

    private void spawn(Placements.NpcRecord npc, World world) {
        despawn(npc.id());
        Location at = new Location(world, npc.x(), npc.y(), npc.z(), npc.yaw(), npc.pitch());
        EntityType type = entityType(npc.entity());
        Entity body = world.spawnEntity(at, type);
        body.setPersistent(false);
        body.setInvulnerable(true);
        body.setSilent(true);
        body.setGravity(false);
        body.getPersistentDataContainer().set(key, PersistentDataType.STRING, npc.id());
        if (body instanceof LivingEntity living) {
            living.setAI(false);
            living.setCollidable(false);
            living.setRemoveWhenFarAway(false);
        }
        bodies.put(npc.id(), body);

        double height = plugin.getConfig().getDouble("npcs.label-height", 2.3);
        TextDisplay label = world.spawn(at.clone().add(0, height, 0), TextDisplay.class, display -> {
            display.setPersistent(false);
            display.setBillboard(Display.Billboard.CENTER);
            display.text(label(npc.game()));
            display.getPersistentDataContainer().set(key, PersistentDataType.STRING, npc.id());
        });
        labels.put(npc.id(), label);
    }

    private Component label(String game) {
        NetworkView.Game status = plugin.network().game(game);
        Map<String, Object> values = Map.of("game", game, "players", status.players(), "servers", status.servers(),
                // From the config, so MiniMessage itself rather than plain text.
                "status", Text.render(plugin.getConfig().getString("npcs.status." + status.status().name()
                        .toLowerCase(Locale.ROOT), status.status().name())));
        List<String> lines = plugin.getConfig().getStringList("npcs.label");
        if (lines.isEmpty()) {
            lines = List.of("<aqua><bold>{game}", "<gray>{players} playing");
        }
        Component text = Component.empty();
        for (int index = 0; index < lines.size(); index++) {
            text = text.append(Text.render(lines.get(index), values));
            if (index + 1 < lines.size()) {
                text = text.append(Component.newline());
            }
        }
        return text;
    }

    private void despawn(String id) {
        Entity body = bodies.remove(id);
        if (body != null) {
            body.remove();
        }
        TextDisplay label = labels.remove(id);
        if (label != null) {
            label.remove();
        }
    }

    public void despawnAll() {
        new ArrayList<>(bodies.keySet()).forEach(this::despawn);
    }

    private static EntityType entityType(String name) {
        try {
            EntityType type = EntityType.valueOf(name.toUpperCase(Locale.ROOT));
            return type.isSpawnable() && type.isAlive() ? type : EntityType.VILLAGER;
        } catch (IllegalArgumentException | NullPointerException unknown) {
            return EntityType.VILLAGER;
        }
    }

    // ---------------------------------------------------------------- players

    private Optional<Placements.NpcRecord> npcOf(Entity entity) {
        String id = entity.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        return id == null ? Optional.empty() : Optional.ofNullable(npcs.get(id));
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        npcOf(event.getRightClicked()).ifPresent(npc -> {
            // Before the villager's trade window, or anything else, opens.
            event.setCancelled(true);
            plugin.joinGame(event.getPlayer(), npc.game(), true);
        });
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteractAt(PlayerInteractAtEntityEvent event) {
        if (npcOf(event.getRightClicked()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onHit(EntityDamageByEntityEvent event) {
        npcOf(event.getEntity()).ifPresent(npc -> {
            event.setCancelled(true);
            if (event.getDamager() instanceof Player player) {
                plugin.joinGame(player, npc.game(), true);
            }
        });
    }

    // ---------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can place NPCs.");
            return true;
        }
        String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "create" -> create(player, args);
            case "remove" -> remove(player);
            case "list" -> list(player);
            default -> player.sendMessage(Text.render(
                    "<gray>/cloudnpc create <game> [entity] <dark_gray>- <gray>an NPC here that queues for a game\n"
                            + "<gray>/cloudnpc remove <dark_gray>- <gray>the nearest NPC\n"
                            + "<gray>/cloudnpc list"));
        }
        return true;
    }

    private void create(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Text.render("<red>Usage: /cloudnpc create <game> [entity]"));
            return;
        }
        var group = plugin.network().group(args[1]);
        if (group.isEmpty()) {
            player.sendMessage(Text.render("<red>There is no group called <white>{game}<red>.",
                    Map.of("game", args[1])));
            return;
        }
        String entity = args.length > 2 ? entityType(args[2]).name() : "VILLAGER";
        Location at = player.getLocation();
        Placements.NpcRecord npc = new Placements.NpcRecord(UUID.randomUUID().toString(), plugin.lobbyGroup(),
                at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), at.getYaw(), 0, entity,
                group.get().name());
        npcs.put(npc.id(), npc);
        Placements.save(npc);
        spawn(npc, at.getWorld());
        player.sendMessage(Text.render("<green>NPC for <white>{game}<green> created. Every lobby server shows it.",
                Map.of("game", group.get().name())));
    }

    private void remove(Player player) {
        Location at = player.getLocation();
        Optional<Placements.NpcRecord> nearest = npcs.values().stream()
                .filter(npc -> npc.world().equals(at.getWorld().getName()))
                .filter(npc -> distanceSquared(npc, at) <= 9)
                .min(Comparator.comparingDouble(npc -> distanceSquared(npc, at)));
        if (nearest.isEmpty()) {
            player.sendMessage(Text.render("<red>No NPC within 3 blocks."));
            return;
        }
        npcs.remove(nearest.get().id());
        despawn(nearest.get().id());
        Placements.deleteNpc(nearest.get().id());
        player.sendMessage(Text.render("<yellow>NPC for <white>{game}<yellow> removed from every lobby server.",
                Map.of("game", nearest.get().game())));
    }

    private void list(Player player) {
        if (npcs.isEmpty()) {
            player.sendMessage(Text.render("<gray>No NPCs yet. <white>/cloudnpc create <game>"));
            return;
        }
        for (Placements.NpcRecord npc : npcs.values()) {
            player.sendMessage(Text.render("<white>{game} <gray>{entity} at {world} {x} {y} {z}", Map.of(
                    "game", npc.game(), "entity", npc.entity().toLowerCase(Locale.ROOT), "world", npc.world(),
                    "x", (int) npc.x(), "y", (int) npc.y(), "z", (int) npc.z())));
        }
    }

    private static double distanceSquared(Placements.NpcRecord npc, Location at) {
        double dx = npc.x() - at.getX();
        double dy = npc.y() - at.getY();
        double dz = npc.z() - at.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("create", "remove", "list");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("create")) {
            return plugin.network().gameGroups();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("create")) {
            return Arrays.stream(EntityType.values()).filter(type -> type.isSpawnable() && type.isAlive())
                    .map(type -> type.name().toLowerCase(Locale.ROOT))
                    .filter(name -> name.startsWith(args[2].toLowerCase(Locale.ROOT))).limit(30).toList();
        }
        return List.of();
    }
}
