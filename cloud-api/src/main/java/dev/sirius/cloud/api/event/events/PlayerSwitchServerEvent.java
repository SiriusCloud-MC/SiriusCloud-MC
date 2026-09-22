package dev.sirius.cloud.api.event.events;

import dev.sirius.cloud.api.event.Event;
import dev.sirius.cloud.api.player.CloudPlayer;

/**
 * Fired when a player lands on a backend server.
 *
 * @param previousServer the server they came from, or null on their first
 */
public record PlayerSwitchServerEvent(CloudPlayer player, String previousServer) implements Event {
}
