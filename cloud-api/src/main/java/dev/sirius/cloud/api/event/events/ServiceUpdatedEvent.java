package dev.sirius.cloud.api.event.events;

import dev.sirius.cloud.api.event.Event;
import dev.sirius.cloud.api.service.ServiceInfo;

/**
 * Something about a service changed: its properties, its player count or its
 * capacity. State changes have their own {@link ServiceStateChangedEvent}.
 *
 * <p>Fired on the node and, since services are told about each other, on every
 * connected server and proxy too - so a sign wall on a lobby can react to a
 * game going {@code INGAME} on another machine without polling.
 */
public record ServiceUpdatedEvent(ServiceInfo service) implements Event {
}
