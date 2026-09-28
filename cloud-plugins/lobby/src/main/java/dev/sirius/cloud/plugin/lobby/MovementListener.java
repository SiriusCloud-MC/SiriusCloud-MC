package dev.sirius.cloud.plugin.lobby;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.util.Vector;

/**
 * Getting around: double jump, launch pads, and no falling out of the world.
 *
 * <p>Double jump uses the flight toggle - pressing jump in the air - so it
 * only applies to players who cannot really fly. Players with
 * {@code siriuscloud.lobby.fly} fly normally instead.
 */
public final class MovementListener implements Listener {

    private final LobbyPlugin plugin;

    public MovementListener(LobbyPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean doubleJumps(Player player) {
        return plugin.getConfig().getBoolean("movement.double-jump", true)
                && !player.hasPermission("siriuscloud.lobby.fly")
                && (player.getGameMode() == GameMode.ADVENTURE || player.getGameMode() == GameMode.SURVIVAL)
                && !plugin.building(player);
    }

    /** Called on join and after changes, so the jump is ready from the start. */
    public void prepare(Player player) {
        if (player.hasPermission("siriuscloud.lobby.fly")) {
            player.setAllowFlight(true);
        } else if (doubleJumps(player)) {
            player.setAllowFlight(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onJump(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        if (!event.isFlying() || !doubleJumps(player)) {
            return;
        }
        event.setCancelled(true);
        player.setFlying(false);
        player.setAllowFlight(false);
        Vector push = player.getLocation().getDirection().setY(0).normalize()
                .multiply(plugin.getConfig().getDouble("movement.double-jump-power", 1.1))
                .setY(plugin.getConfig().getDouble("movement.double-jump-height", 0.9));
        player.setVelocity(push);
        player.playSound(player.getLocation(), Sound.ENTITY_BAT_TAKEOFF, 0.6f, 1.4f);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (event.getTo().getY() < plugin.getConfig().getDouble("movement.void-height", 0)) {
            player.teleport(plugin.spawn());
            player.setFallDistance(0);
            return;
        }
        // Landed: the next double jump is ready.
        if (!player.getAllowFlight() && doubleJumps(player) && standing(player)) {
            player.setAllowFlight(true);
        }
    }

    /** Solid ground just below the feet. The player's own on-ground flag is the client's word for it. */
    private static boolean standing(Player player) {
        return player.getLocation().subtract(0, 0.1, 0).getBlock().getType().isSolid();
    }

    @EventHandler
    public void onPad(PlayerInteractEvent event) {
        if (event.getAction() != Action.PHYSICAL || event.getClickedBlock() == null
                || !plugin.getConfig().getBoolean("movement.launch-pads", true)) {
            return;
        }
        Material pad = Hotbar.material(plugin.getConfig().getString("movement.launch-pad-block"),
                Material.LIGHT_WEIGHTED_PRESSURE_PLATE);
        if (event.getClickedBlock().getType() != pad) {
            return;
        }
        Player player = event.getPlayer();
        Vector push = player.getLocation().getDirection().setY(0).normalize()
                .multiply(plugin.getConfig().getDouble("movement.launch-power", 2.4))
                .setY(plugin.getConfig().getDouble("movement.launch-height", 1.0));
        player.setVelocity(push);
        player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.8f, 1.0f);
    }
}
