package dev.sirius.cloud.node.command.commands;

import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.node.command.Command;
import dev.sirius.cloud.node.service.ServiceManager;
import dev.sirius.cloud.node.service.ServiceRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class StopCommand implements Command {

    private static final CloudLogger LOGGER = CloudLogger.of("Console");

    private final ServiceRegistry services;
    private final ServiceManager serviceManager;

    public StopCommand(ServiceRegistry services, ServiceManager serviceManager) {
        this.services = services;
        this.serviceManager = serviceManager;
    }

    @Override
    public String name() {
        return "stop";
    }

    @Override
    public String usage() {
        return "stop <service|group|all> [--force]";
    }

    @Override
    public String description() {
        return "Gracefully stops services (--force kills them)";
    }

    @Override
    public void execute(String[] args) {
        if (args.length < 1) {
            LOGGER.warn("Usage: {}", usage());
            return;
        }

        boolean force = args.length > 1 && args[1].equalsIgnoreCase("--force");
        String target = args[0];

        List<ServiceInfo> targets = new ArrayList<>();

        if (target.equalsIgnoreCase("all")) {
            targets.addAll(services.all());
        } else {
            Optional<ServiceInfo> byName = services.byName(target);
            if (byName.isPresent()) {
                targets.add(byName.get());
            } else {
                targets.addAll(services.ofGroup(target));
            }
        }

        if (targets.isEmpty()) {
            LOGGER.warn("Nothing matches '{}'", target);
            return;
        }

        targets.forEach(service -> serviceManager.stop(service.uniqueId(), force));
    }

    @Override
    public List<String> complete(String[] args) {
        if (args.length <= 1) {
            List<String> candidates = new ArrayList<>(services.all().stream().map(ServiceInfo::name).toList());
            candidates.add("all");
            return candidates;
        }
        return List.of("--force");
    }
}
