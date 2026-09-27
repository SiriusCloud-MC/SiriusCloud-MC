package dev.sirius.cloud.node.provisioning;

import dev.sirius.cloud.api.group.ServiceGroup;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.api.service.ServiceState;
import dev.sirius.cloud.node.group.GroupRegistry;
import dev.sirius.cloud.node.service.ServiceManager;
import dev.sirius.cloud.node.service.ServiceRegistry;
import dev.sirius.cloud.node.wrapper.WrapperRegistry;

import java.util.Optional;

/**
 * The reconciliation loop that makes this a cloud rather than a process
 * launcher: once a second, compare reality against what every group asks for
 * and close the gap.
 *
 * <p>Four jobs, in this order each tick:
 * <ol>
 *   <li>kill services stuck starting past their group's timeout;
 *   <li>advance rolling replacements;
 *   <li>bring every group up to its minimum;
 *   <li>grow or shrink groups with their player load.
 * </ol>
 * The minimum comes before scaling so that a group short of its floor is
 * refilled before anything decides it is quiet enough to shrink.
 */
public final class ProvisioningTask implements Runnable {

    private static final CloudLogger LOGGER = CloudLogger.of("Provisioning");

    private final GroupRegistry groups;
    private final ServiceRegistry services;
    private final ServiceManager serviceManager;
    private final WrapperRegistry wrappers;
    private final GroupBackoff backoff;
    private final AutoScaler autoScaler;
    private final Rollouts rollouts;

    public ProvisioningTask(GroupRegistry groups,
                            ServiceRegistry services,
                            ServiceManager serviceManager,
                            WrapperRegistry wrappers,
                            GroupBackoff backoff,
                            AutoScaler autoScaler,
                            Rollouts rollouts) {
        this.groups = groups;
        this.services = services;
        this.serviceManager = serviceManager;
        this.wrappers = wrappers;
        this.backoff = backoff;
        this.autoScaler = autoScaler;
        this.rollouts = rollouts;
    }

    @Override
    public void run() {
        long now = System.currentTimeMillis();

        enforceStartTimeouts(now);
        rollouts.tick(now);

        // Nothing can be scheduled without a machine to schedule onto.
        if (wrappers.isEmpty()) {
            return;
        }

        for (ServiceGroup group : groups.all()) {
            if (group.maintenance()) {
                continue;
            }

            long active = services.activeCount(group.name());
            if (active < group.minServiceCount()) {
                // A group that keeps failing is retried on a growing delay
                // rather than once a second; see GroupBackoff.
                if (backoff.ready(group.name())) {
                    // One per tick. Starting the whole deficit at once would spawn
                    // a thundering herd of JVMs all contending for disk and CPU.
                    serviceManager.start(group).whenComplete((service, error) -> {
                        if (error != null) {
                            LOGGER.debug("Cannot provision {} yet: {}", group.name(), error.getMessage());
                        }
                    });
                }
                continue;
            }

            scale(group, now);
        }
    }

    private void scale(ServiceGroup group, long now) {
        AutoScaler.Decision decision = autoScaler.decide(
                group, services.ofGroup(group.name()), rollouts.involved(), now);

        switch (decision.action()) {
            case SCALE_UP -> {
                if (!backoff.ready(group.name())) {
                    return;
                }
                serviceManager.start(group).whenComplete((service, error) -> {
                    if (error != null) {
                        LOGGER.debug("Wanted to grow {} but could not: {}", group.name(), error.getMessage());
                    } else {
                        LOGGER.info("{} - starting {}", decision.reason(), service.name());
                    }
                });
            }
            case SCALE_DOWN -> {
                LOGGER.info("{} - stopping it", decision.reason());
                serviceManager.stop(decision.target().uniqueId(), false);
            }
            default -> {
            }
        }
    }

    /**
     * Stops anything that has been starting for too long, and counts it as a
     * failure so the group backs off rather than retrying at once.
     *
     * <p>Without this a server that spawns but never becomes ready - a
     * deadlocked plugin, a world that will not load - holds its name, port and
     * memory forever. Measured from when it entered the state, not from when it
     * was created, so an adopted service is not killed for being old.
     */
    private void enforceStartTimeouts(long now) {
        for (ServiceInfo service : services.all()) {
            if (service.state() != ServiceState.PREPARED && service.state() != ServiceState.STARTING) {
                continue;
            }
            Optional<ServiceGroup> group = groups.byName(service.groupName());
            long timeout = group.map(ServiceGroup::startTimeoutSeconds).orElse(180) * 1000L;
            if (now - service.stateSince() < timeout) {
                continue;
            }
            LOGGER.warn("{} has not become ready after {}s; stopping it", service.name(), timeout / 1000);
            serviceManager.stop(service.uniqueId(), true);
            backoff.recordFailure(service.groupName());
        }
    }
}
