package dev.sirius.cloud.api.event.events;

import dev.sirius.cloud.api.event.Event;
import dev.sirius.cloud.api.node.WrapperInfo;

/** Fired when a wrapper drops. Its services are marked CRASHED by the node. */
public record WrapperDisconnectedEvent(WrapperInfo wrapper) implements Event {
}
