package dev.sirius.cloud.node.command.commands;

import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.node.command.Command;
import dev.sirius.cloud.node.service.ServiceManager;
import dev.sirius.cloud.node.service.ServiceRegistry;

import java.util.List;
import java.util.Optional;

public final class ExecuteCommand implements Command {

    private static final CloudLogger LOGGER = CloudLogger.of("Console");

    private final ServiceRegistry services;
    private final ServiceManager serviceManager;

    public ExecuteCommand(ServiceRegistry services, ServiceManager serviceManager) {
        this.services = services;
        this.serviceManager = serviceManager;
    }

    @Override
    public String name() {
        return "exec";
    }

    @Override
    public List<String> aliases() {
        return List.of("cmd");
    }

    @Override
    public String usage() {
        return "exec <service> <command...>";
    }

    @Override
    public String description() {
        return "Runs a console command inside a service";
    }

    @Override
    public void execute(String[] args) {
        if (args.length < 2) {
            LOGGER.warn("Usage: {}", usage());
            return;
        }

        Optional<ServiceInfo> service = services.byName(args[0]);
        if (service.isEmpty()) {
            LOGGER.warn("No service named '{}'", args[0]);
            return;
        }

        String command = String.join(" ", List.of(args).subList(1, args.length));
        serviceManager.dispatchCommand(service.get().uniqueId(), command);
        LOGGER.info("-> {}: {}", service.get().name(), command);
    }

    @Override
    public List<String> complete(String[] args) {
        return args.length <= 1
                ? services.all().stream().map(ServiceInfo::name).toList()
                : List.of();
    }
}
