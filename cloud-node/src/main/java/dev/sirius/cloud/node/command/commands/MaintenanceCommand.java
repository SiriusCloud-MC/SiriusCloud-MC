package dev.sirius.cloud.node.command.commands;

import dev.sirius.cloud.api.group.ServiceGroup;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.node.command.Command;
import dev.sirius.cloud.node.group.GroupRegistry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Toggles a group's maintenance flag.
 *
 * <p>The flag has always been respected by the provisioning loop and there was
 * no way to set it without editing JSON and restarting, which made it useless
 * in exactly the moment it is wanted: something is wrong with a group right
 * now and you want the node to stop starting more of it.
 */
public final class MaintenanceCommand implements Command {

    private static final CloudLogger LOGGER = CloudLogger.of("Console");

    private final GroupRegistry groups;

    public MaintenanceCommand(GroupRegistry groups) {
        this.groups = groups;
    }

    @Override
    public String name() {
        return "maintenance";
    }

    @Override
    public List<String> aliases() {
        return List.of("mt");
    }

    @Override
    public String usage() {
        return "maintenance <group> [on|off]";
    }

    @Override
    public String description() {
        return "Stops the node provisioning a group, or resumes it";
    }

    @Override
    public void execute(String[] args) {
        if (args.length < 1) {
            groups.all().forEach(group -> LOGGER.info("{}: {}",
                    group.name(), group.maintenance() ? "maintenance" : "active"));
            return;
        }

        Optional<ServiceGroup> found = groups.byName(args[0]);
        if (found.isEmpty()) {
            LOGGER.warn("No group named '{}'. Try 'groups'.", args[0]);
            return;
        }
        ServiceGroup group = found.get();

        boolean enable = args.length > 1
                ? args[1].toLowerCase(Locale.ROOT).matches("on|true|yes|enable")
                : !group.maintenance();

        group.maintenance(enable);
        try {
            groups.save(group);
        } catch (IOException exception) {
            LOGGER.error("Could not save " + group.name(), exception);
            return;
        }

        if (enable) {
            LOGGER.info("{} is in maintenance; the node will not start more of it.", group.name());
            // Said out loud because the alternative reading is "maintenance
            // stops the group", and someone expecting that would be surprised
            // to find their servers still serving players.
            LOGGER.info("Services already running are left alone. Stop them yourself if you meant to.");
        } else {
            LOGGER.info("{} is active again; provisioning resumes.", group.name());
        }
    }

    @Override
    public List<String> complete(String[] args) {
        if (args.length <= 1) {
            List<String> names = new ArrayList<>();
            groups.all().forEach(group -> names.add(group.name()));
            return names;
        }
        return args.length == 2 ? List.of("on", "off") : List.of();
    }
}
