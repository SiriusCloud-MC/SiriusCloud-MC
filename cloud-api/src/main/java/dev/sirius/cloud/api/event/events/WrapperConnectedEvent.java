package dev.sirius.cloud.api.event.events;

import dev.sirius.cloud.api.event.Event;
import dev.sirius.cloud.api.node.WrapperInfo;

/** Fired when a wrapper completes its handshake and becomes schedulable. */
public record WrapperConnectedEvent(WrapperInfo wrapper) implements Event {
}
