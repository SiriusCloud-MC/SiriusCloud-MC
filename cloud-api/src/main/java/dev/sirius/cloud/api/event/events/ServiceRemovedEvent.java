package dev.sirius.cloud.api.event.events;

import dev.sirius.cloud.api.event.Event;
import dev.sirius.cloud.api.service.ServiceInfo;

/** Fired once a service has left the registry and released its port. */
public record ServiceRemovedEvent(ServiceInfo service) implements Event {
}
