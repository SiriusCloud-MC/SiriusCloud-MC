package dev.sirius.cloud.api.event.events;

import dev.sirius.cloud.api.event.Event;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.api.service.ServiceState;

/** Fired on every lifecycle transition of a service. */
public record ServiceStateChangedEvent(ServiceInfo service,
                                       ServiceState previous,
                                       ServiceState current) implements Event {
}
