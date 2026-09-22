package dev.sirius.cloud.node.command.commands;

import dev.sirius.cloud.api.group.ServiceGroup;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.node.command.Command;
import dev.sirius.cloud.node.group.GroupRegistry;
import dev.sirius.cloud.node.service.ServiceRegistry;

import java.util.Collection;

public final class GroupsCommand implements Command {

    private final GroupRegistry groups;
    private final ServiceRegistry services;

    public GroupsCommand(GroupRegistry groups, ServiceRegistry services) {
        this.groups = groups;
        this.services = services;
    }

    @Override
    public String name() {
        return "groups";
    }

    @Override
    public String description() {
        return "Lists configured groups and how many of each are online";
    }

    @Override
    public void execute(String[] args) {
        Collection<ServiceGroup> all = groups.all();
        if (all.isEmpty()) {
            CloudLogger.raw("No groups are configured.");
            return;
        }

        CloudLogger.raw(String.format("%-18s %-8s %-10s %-8s %-10s %s",
                "NAME", "TYPE", "ONLINE", "MEMORY", "PORT", "VERSION"));

        all.forEach(group -> CloudLogger.raw(String.format("%-18s %-8s %-10s %-8s %-10s %s",
                group.name(),
                group.type(),
                services.activeCount(group.name()) + "/" + group.minServiceCount()
                        + "-" + group.maxServiceCount(),
                group.memory() + "MB",
                group.startPort() + "+",
                group.version() + (group.maintenance() ? "  [maintenance]" : ""))));
    }
}
