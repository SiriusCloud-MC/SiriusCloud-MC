package dev.sirius.cloud.node.command.commands;

import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.node.command.Command;
import dev.sirius.cloud.node.provisioning.BackupScheduler;
import dev.sirius.cloud.node.service.ServiceRegistry;

import java.util.List;

/** Backs up a running service now, whatever its group's schedule. */
public final class BackupCommand implements Command {

    private static final CloudLogger LOGGER = CloudLogger.of("Backup");

    private final ServiceRegistry services;
    private final BackupScheduler backups;

    public BackupCommand(ServiceRegistry services, BackupScheduler backups) {
        this.services = services;
        this.backups = backups;
    }

    @Override
    public String name() {
        return "backup";
    }

    @Override
    public String usage() {
        return "backup <service>";
    }

    @Override
    public String description() {
        return "Saves and archives a running service on its wrapper now";
    }

    @Override
    public void execute(String[] args) {
        if (args.length < 1) {
            LOGGER.warn("Usage: {}", usage());
            return;
        }
        services.byName(args[0]).ifPresentOrElse(
                service -> backups.request(service).ifPresentOrElse(
                        problem -> LOGGER.warn("Cannot back up: {}", problem),
                        () -> LOGGER.info("Backing up {} - the wrapper reports when it is done", service.name())),
                () -> LOGGER.warn("No service named '{}'", args[0]));
    }

    @Override
    public List<String> complete(String[] args) {
        return args.length <= 1 ? services.all().stream().map(ServiceInfo::name).toList() : List.of();
    }
}
