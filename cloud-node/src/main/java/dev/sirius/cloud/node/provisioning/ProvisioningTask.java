package dev.sirius.cloud.node.provisioning;

import dev.sirius.cloud.api.group.ServiceGroup;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.node.group.GroupRegistry;
import dev.sirius.cloud.node.service.ServiceManager;
import dev.sirius.cloud.node.service.ServiceRegistry;
import dev.sirius.cloud.node.wrapper.WrapperRegistry;

/**
 * The reconciliation loop that makes this a cloud rather than a process
 * launcher: once a second, compare reality against every group's
 * {@code minServiceCount} and close the gap.
 *
 * <p>It is here from the first commit deliberately. Retrofitting declarative
 * reconciliation onto a codebase built around imperative start/stop calls means
 * unpicking every call site, so the loop owns capacity from the beginning and
 * manual commands are just a nudge to the same machinery.
 */
public final class ProvisioningTask implements Runnable {

    private static final CloudLogger LOGGER = CloudLogger.of("Provisioning");

    private final GroupRegistry groups;
    private final ServiceRegistry services;
    private final ServiceManager serviceManager;
    private final WrapperRegistry wrappers;

    public ProvisioningTask(GroupRegistry groups,
                            ServiceRegistry services,
                            ServiceManager serviceManager,
                            WrapperRegistry wrappers) {
        this.groups = groups;
        this.services = services;
        this.serviceManager = serviceManager;
        this.wrappers = wrappers;
    }

    @Override
    public void run() {
        // Nothing can be scheduled without a machine to schedule onto.
        if (wrappers.isEmpty()) {
            return;
        }

        for (ServiceGroup group : groups.all()) {
            if (group.maintenance()) {
                continue;
            }

            long active = services.activeCount(group.name());
            if (active >= group.minServiceCount()) {
                continue;
            }

            // One per tick. Starting the whole deficit at once would spawn a
            // thundering herd of JVMs that all contend for disk and CPU.
            serviceManager.start(group).whenComplete((service, error) -> {
                if (error != null) {
                    LOGGER.debug("Cannot provision {} yet: {}", group.name(), error.getMessage());
                }
            });
        }
    }
}
