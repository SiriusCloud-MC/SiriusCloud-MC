package dev.sirius.cloud.node.command.commands;

import dev.sirius.cloud.api.group.ServiceGroup;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.node.command.Command;
import dev.sirius.cloud.node.group.GroupRegistry;
import dev.sirius.cloud.node.provisioning.Rollouts;

import java.util.List;
import java.util.Map;

/** Replaces a group's services one at a time; see {@link Rollouts}. */
public final class RolloutCommand implements Command {

    private static final CloudLogger LOGGER = CloudLogger.of("Rollout");

    private final Rollouts rollouts;
    private final GroupRegistry groups;

    public RolloutCommand(Rollouts rollouts, GroupRegistry groups) {
        this.rollouts = rollouts;
        this.groups = groups;
    }

    @Override
    public String name() {
        return "rollout";
    }

    @Override
    public String usage() {
        return "rollout [<group> [cancel]]";
    }

    @Override
    public String description() {
        return "Restarts a group one service at a time, keeping it serving";
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 0) {
            Map<String, String> running = rollouts.describe();
            if (running.isEmpty()) {
                CloudLogger.raw("No rollouts are running. Start one with 'rollout <group>'.");
                return;
            }
            running.forEach((group, status) -> CloudLogger.raw("  " + group + ": " + status));
            return;
        }

        if (args.length > 1 && args[1].equalsIgnoreCase("cancel")) {
            if (!rollouts.cancel(args[0])) {
                LOGGER.warn("No rollout of {} is running", args[0]);
            }
            return;
        }

        rollouts.start(args[0], "requested from the console")
                .ifPresent(problem -> LOGGER.warn("{}", problem));
    }

    @Override
    public List<String> complete(String[] args) {
        if (args.length <= 1) {
            return groups.all().stream().map(ServiceGroup::name).toList();
        }
        return List.of("cancel");
    }
}
