package dev.sirius.cloud.api.event.events;

import dev.sirius.cloud.api.event.Event;
import dev.sirius.cloud.api.service.ServiceInfo;

/**
 * A service reported its health: tick rate, tick time, heap. Every ten seconds
 * per service, on the node only.
 *
 * <p>What a lag alert or a metrics exporter listens to.
 */
public record ServiceMetricsEvent(ServiceInfo service) implements Event {
}
