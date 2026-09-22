package dev.sirius.cloud.node.command.commands;

import dev.sirius.cloud.api.group.ServiceGroup;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.node.command.Command;
import dev.sirius.cloud.node.group.GroupRegistry;
import dev.sirius.cloud.node.service.ServiceManager;

import java.util.List;

public final class StartCommand implements Command {

    private static final CloudLogger LOGGER = CloudLogger.of("Console");

    private final GroupRegistry groups;
    private final ServiceManager serviceManager;

    public StartCommand(GroupRegistry groups, ServiceManager serviceManager) {
        this.groups = groups;
        this.serviceManager = serviceManager;
    }

    @Override
    public String name() {
        return "start";
    }

    @Override
    public String usage() {
        return "start <group> [count]";
    }

    @Override
    public String description() {
        return "Starts one or more services of a group";
    }

    @Override
    public void execute(String[] args) {
        if (args.length < 1) {
            LOGGER.warn("Usage: {}", usage());
            return;
        }

        String groupName = args[0];
        if (groups.byName(groupName).isEmpty()) {
            LOGGER.warn("No group named '{}'. Try 'groups'.", groupName);
            return;
        }

        int count = 1;
        if (args.length > 1) {
            try {
                count = Math.max(1, Integer.parseInt(args[1]));
            } catch (NumberFormatException exception) {
                LOGGER.warn("'{}' is not a number", args[1]);
                return;
            }
        }

        for (int i = 0; i < count; i++) {
            serviceManager.start(groupName).whenComplete((service, error) -> {
                if (error != null) {
                    LOGGER.warn("Could not start: {}", rootMessage(error));
                }
            });
        }
    }

    @Override
    public List<String> complete(String[] args) {
        if (args.length <= 1) {
            return groups.all().stream().map(ServiceGroup::name).toList();
        }
        return List.of();
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
