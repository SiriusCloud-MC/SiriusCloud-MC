package dev.sirius.cloud.plugin.paper;

import dev.sirius.cloud.protocol.packet.impl.ChatRestrictionsPacket;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stops restricted players chatting, on this server.
 *
 * <p>Enforced here rather than on the proxy on purpose. Chat has been signed
 * since 1.19.1, and a proxy that cancels a signed message leaves the client's
 * message chain broken - the player is disconnected the next time they speak.
 * A server can cancel its own chat event cleanly.
 *
 * <p>The node sends the complete list whenever it changes and when this server
 * connects, so a server that was offline during a mute is correct the moment it
 * is back.
 */
final class ChatGuard implements Listener {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Map<UUID, ChatRestrictionsPacket.Restriction> restrictions = new ConcurrentHashMap<>();

    void replace(ChatRestrictionsPacket packet) {
        restrictions.clear();
        packet.restrictions().forEach(restriction -> restrictions.put(restriction.playerId(), restriction));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        ChatRestrictionsPacket.Restriction restriction = restrictions.get(event.getPlayer().getUniqueId());
        if (restriction == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (restriction.untilMillis() > 0 && restriction.untilMillis() <= now) {
            // Lapsed. Dropped here so it does not linger until the next sync.
            restrictions.remove(event.getPlayer().getUniqueId(), restriction);
            return;
        }

        event.setCancelled(true);
        String remaining = restriction.untilMillis() == 0
                ? "permanently"
                : "for another " + describe(Duration.ofMillis(restriction.untilMillis() - now));
        String reason = restriction.reason().isBlank() ? "" : " <gray>(" + restriction.reason() + ")";
        event.getPlayer().sendMessage(MINI.deserialize("<red>You are muted " + remaining + "." + reason));
    }

    private static String describe(Duration left) {
        long minutes = Math.max(1, left.toMinutes());
        if (minutes < 60) {
            return minutes + "m";
        }
        long hours = minutes / 60;
        if (hours < 48) {
            return hours + "h " + (minutes % 60) + "m";
        }
        return (hours / 24) + "d " + (hours % 24) + "h";
    }
}
