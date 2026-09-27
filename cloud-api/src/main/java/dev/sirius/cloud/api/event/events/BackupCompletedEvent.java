package dev.sirius.cloud.api.event.events;

import dev.sirius.cloud.api.event.Event;

/**
 * A backup finished, or failed. Posted on the node.
 *
 * @param file where the archive was written on the wrapper; empty on failure
 */
public record BackupCompletedEvent(String serviceName, String wrapperName, boolean success,
                                   String file, long sizeBytes, String message) implements Event {
}
