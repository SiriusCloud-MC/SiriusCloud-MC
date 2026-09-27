package dev.sirius.cloud.wrapper.process;

import dev.sirius.cloud.api.group.ServiceGroup;
import dev.sirius.cloud.api.service.ServiceInfo;

import java.nio.file.Path;
import java.util.List;

/**
 * How a service's JVM is actually run: directly, or inside a container.
 *
 * <p>Everything else about a service - its directory, its config, its console,
 * the graceful stop through stdin - is the same either way, which is the point
 * of drawing the line here.
 */
public interface ServiceLauncher {

    /** Whether the service runs in a container, whose network and filesystem differ from the host's. */
    boolean containerised();

    /**
     * The command to spawn.
     *
     * @param javaCommand the JVM invocation as it would run on this host,
     *                    starting with the java binary
     */
    List<String> command(ServiceInfo info, ServiceGroup group, Path directory, List<String> javaCommand);

    /**
     * Stops a service that ignored the graceful shutdown command.
     *
     * <p>Blocking, and escalating: whatever the launcher's polite version of
     * "stop now" is, then whatever cannot be refused.
     */
    void kill(ServiceInfo info, Process process);

    /** The node's address as the service itself must dial it. */
    String nodeHostFor(String nodeHost);
}
