package dev.sirius.cloud.api.event.events;

import dev.sirius.cloud.api.event.Event;

import java.util.List;

/**
 * A service died without being asked to, with the last thing it printed.
 *
 * <p>Separate from the state change to {@code CRASHED} because it carries the
 * reason, which is what anyone being alerted actually needs to read.
 */
public record ServiceCrashedEvent(String serviceName, String groupName, int exitCode,
                                  List<String> lastLines) implements Event {
}
