package dev.sirius.cloud.api.event.events;

import dev.sirius.cloud.api.event.Event;
import dev.sirius.cloud.api.node.WrapperInfo;

/**
 * Fired when a wrapper drops.
 *
 * <p>Its services keep running — the wrapper does not stop them, so that losing
 * the control connection cannot disconnect players — and the node keeps their
 * records, holding their names and ports, until the wrapper reconnects and says
 * what is actually still alive. Nothing can be started on or stopped on that
 * machine in the meantime.
 */
public record WrapperDisconnectedEvent(WrapperInfo wrapper) implements Event {
}
