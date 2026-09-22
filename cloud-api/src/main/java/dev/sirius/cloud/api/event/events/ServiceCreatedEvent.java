package dev.sirius.cloud.api.event.events;

import dev.sirius.cloud.api.event.Event;
import dev.sirius.cloud.api.service.ServiceInfo;

/** Fired once the node has reserved a name, port and slot for a new service. */
public record ServiceCreatedEvent(ServiceInfo service) implements Event {
}
