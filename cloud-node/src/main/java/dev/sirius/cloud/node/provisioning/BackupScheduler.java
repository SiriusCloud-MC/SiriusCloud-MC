package dev.sirius.cloud.node.provisioning;

import dev.sirius.cloud.api.group.ServiceGroup;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.api.service.ServiceState;
import dev.sirius.cloud.api.service.ServiceType;
import dev.sirius.cloud.node.group.GroupRegistry;
import dev.sirius.cloud.node.service.ServiceRegistry;
import dev.sirius.cloud.node.wrapper.WrapperRegistry;
import dev.sirius.cloud.protocol.packet.impl.BackupRequestPacket;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Asks wrappers to back up static services on each group's interval.
 *
 * <p>The first backup of a service is one interval after it is first seen, not
 * immediately. Otherwise restarting the node would back up every static
 * server at once, which is a lot of disk I/O at exactly the moment everything
 * is starting up.
 */
public final class BackupScheduler {

    private static final CloudLogger LOGGER = CloudLogger.of("Backup");

    private final GroupRegistry groups;
    private final ServiceRegistry services;
    private final WrapperRegistry wrappers;
    private final Map<UUID, Long> lastBackup = new ConcurrentHashMap<>();

    public BackupScheduler(GroupRegistry groups, ServiceRegistry services, WrapperRegistry wrappers) {
        this.groups = groups;
        this.services = services;
        this.wrappers = wrappers;
    }

    public void tick() {
        long now = System.currentTimeMillis();
        for (ServiceInfo service : services.all()) {
            if (service.state() != ServiceState.RUNNING || service.type() != ServiceType.SERVER) {
                continue;
            }
            Optional<ServiceGroup> group = groups.byName(service.groupName());
            if (group.isEmpty() || !group.get().staticService() || group.get().backup().intervalMinutes() <= 0) {
                continue;
            }
            long interval = group.get().backup().intervalMinutes() * 60_000L;
            long last = lastBackup.computeIfAbsent(service.uniqueId(), id -> now);
            if (now - last >= interval && request(service).isEmpty()) {
                lastBackup.put(service.uniqueId(), now);
            }
        }
    }

    /**
     * Asks for a backup now.
     *
     * @return why it could not be asked for, or empty if it was
     */
    public Optional<String> request(ServiceInfo service) {
        if (service.state() != ServiceState.RUNNING) {
            return Optional.of(service.name() + " is " + service.state() + ", not running");
        }
        var wrapper = wrappers.byName(service.wrapperName());
        if (wrapper.isEmpty()) {
            return Optional.of("the wrapper running " + service.name() + " is not connected");
        }
        int keep = groups.byName(service.groupName()).map(group -> group.backup().keep()).orElse(5);
        wrapper.get().send(new BackupRequestPacket(service.uniqueId(), keep));
        LOGGER.debug("Asked {} to back up {}", service.wrapperName(), service.name());
        return Optional.empty();
    }

    public void forget(UUID serviceId) {
        lastBackup.remove(serviceId);
    }
}
