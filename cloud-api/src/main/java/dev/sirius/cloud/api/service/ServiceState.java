package dev.sirius.cloud.api.service;

/**
 * Lifecycle of a service.
 *
 * <pre>
 *   PREPARED -> STARTING -> RUNNING -> STOPPING -> STOPPED
 *                   \                      /
 *                    \-----> CRASHED <----/
 * </pre>
 */
public enum ServiceState {

    /** Registered by the node, not yet handed to a wrapper. */
    PREPARED,

    /** The wrapper has spawned the process; it has not reported readiness yet. */
    STARTING,

    /** The in-service plugin has connected and reported ready. */
    RUNNING,

    /** A graceful shutdown is in progress. */
    STOPPING,

    /** The process exited as instructed. */
    STOPPED,

    /** The process exited without being asked to. */
    CRASHED;

    /** Whether a service in this state still occupies a slot and a port. */
    public boolean isActive() {
        return this == PREPARED || this == STARTING || this == RUNNING || this == STOPPING;
    }

    public boolean isTerminal() {
        return this == STOPPED || this == CRASHED;
    }
}
