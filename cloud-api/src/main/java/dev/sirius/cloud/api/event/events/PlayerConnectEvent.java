package dev.sirius.cloud.api.event.events;

import dev.sirius.cloud.api.event.Event;
import dev.sirius.cloud.api.player.CloudPlayer;

/** Fired when a player joins the cloud through any proxy. */
public record PlayerConnectEvent(CloudPlayer player) implements Event {
}
