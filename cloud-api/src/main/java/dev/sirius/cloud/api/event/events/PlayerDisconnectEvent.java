package dev.sirius.cloud.api.event.events;

import dev.sirius.cloud.api.event.Event;
import dev.sirius.cloud.api.player.CloudPlayer;

/** Fired when a player leaves the cloud entirely. */
public record PlayerDisconnectEvent(CloudPlayer player) implements Event {
}
