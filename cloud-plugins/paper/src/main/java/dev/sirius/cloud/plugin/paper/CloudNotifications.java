package dev.sirius.cloud.plugin.paper;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sirius.cloud.api.driver.CloudDriver;
import dev.sirius.cloud.api.messaging.ChannelMessage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;

/**
 * Shows cloud events to staff on this server.
 *
 * <p>The filtering happens here rather than on the node, because the node has
 * no idea who holds a permission. It publishes "anyone with
 * {@code siriuscloud.notify} should hear this" and each server answers that
 * question for its own players, using whatever permission plugin is installed.
 */
final class CloudNotifications {

    /** Must match the notify module's channel. */
    static final String CHANNEL = "siriuscloud:notify";

    private static final Gson GSON = new Gson();

    private CloudNotifications() {
    }

    static void register() {
        CloudDriver.instance().messaging().subscribe(CHANNEL, CloudNotifications::show);
    }

    private static void show(ChannelMessage message) {
        JsonObject body;
        try {
            body = JsonParser.parseString(message.payload()).getAsJsonObject();
        } catch (RuntimeException exception) {
            // Malformed payload from something else using our channel. Ignore
            // it rather than spamming the log on every message.
            return;
        }

        String permission = optional(body, "permission", "siriuscloud.notify");
        String text = optional(body, "message", "");
        if (text.isBlank()) {
            return;
        }

        Component line = Component.text("[Cloud] ", NamedTextColor.AQUA)
                .append(Component.text(text, colourOf(optional(body, "kind", "INFO"))));

        // Adventure sends are safe off the main thread on Paper, and this
        // arrives on a Netty thread. Hopping to the main thread would delay a
        // crash notice behind whatever the server is busy with.
        for (var player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission(permission)) {
                player.sendMessage(line);
            }
        }
    }

    private static NamedTextColor colourOf(String kind) {
        return switch (kind) {
            case "GOOD" -> NamedTextColor.GREEN;
            case "WARN" -> NamedTextColor.YELLOW;
            case "BAD" -> NamedTextColor.RED;
            default -> NamedTextColor.GRAY;
        };
    }

    private static String optional(JsonObject body, String key, String fallback) {
        return body.has(key) && !body.get(key).isJsonNull() ? body.get(key).getAsString() : fallback;
    }
}
