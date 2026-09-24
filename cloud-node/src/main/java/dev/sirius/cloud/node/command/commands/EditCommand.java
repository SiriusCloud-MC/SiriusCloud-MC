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
 * Changes one field of a group and saves it.
 *
 * <p>{@code setup} creates groups; until now changing one meant stopping the
 * node, editing JSON and starting again. Everything here is editable live, but
 * only ever affects services started afterwards, which is said on every change
 * rather than left to be discovered.
 */
public final class EditCommand implements Command {

    private static final CloudLogger LOGGER = CloudLogger.of("Console");

    private static final List<String> FIELDS = List.of(
            "memory", "minmemory", "min", "max", "maxplayers",
            "version", "build", "port", "static", "fallback", "java");

    private final GroupRegistry groups;

    public EditCommand(GroupRegistry groups) {
        this.groups = groups;
    }

    @Override
    public String name() {
        return "edit";
    }

    @Override
    public String usage() {
        return "edit <group> <field> <value>";
    }

    @Override
    public String description() {
        return "Changes a group setting (see 'edit' for the field list)";
    }

    @Override
    public void execute(String[] args) {
        if (args.length < 3) {
            LOGGER.warn("Usage: {}", usage());
            LOGGER.info("Fields: {}", String.join(", ", FIELDS));
            LOGGER.info("Maintenance has its own command: 'maintenance <group> on|off'.");
            return;
        }

        Optional<ServiceGroup> found = groups.byName(args[0]);
        if (found.isEmpty()) {
            LOGGER.warn("No group named '{}'. Try 'groups'.", args[0]);
            return;
        }
        ServiceGroup group = found.get();

        String field = args[1].toLowerCase(Locale.ROOT);
        String value = String.join(" ", List.of(args).subList(2, args.length));

        try {
            switch (field) {
                case "memory" -> group.memory(positive(value));
                case "minmemory" -> group.minMemory(positive(value));
                case "min" -> group.minServiceCount(whole(value));
                case "max" -> group.maxServiceCount(whole(value));
                case "maxplayers" -> group.maxPlayers(positive(value));
                case "version" -> group.version(value);
                case "build" -> group.build(value);
                case "port" -> group.startPort(port(value));
                case "static" -> group.staticService(flag(value));
                case "fallback" -> group.fallback(flag(value));
                case "java" -> group.javaExecutable(value.equalsIgnoreCase("default") ? "" : value);
                default -> {
                    LOGGER.warn("Unknown field '{}'. One of: {}", field, String.join(", ", FIELDS));
                    return;
                }
            }
        } catch (IllegalArgumentException exception) {
            LOGGER.warn("{}", exception.getMessage());
            return;
        }

        // Caught here rather than at start time, where it would present as a
        // group that accepts its configuration and then refuses to run.
        if (group.minServiceCount() > group.maxServiceCount()) {
            LOGGER.warn("{} now keeps {} online but allows at most {}; the loop will never settle.",
                    group.name(), group.minServiceCount(), group.maxServiceCount());
        }

        try {
            groups.save(group);
        } catch (IOException exception) {
            LOGGER.error("Could not save " + group.name(), exception);
            return;
        }

        LOGGER.info("{}: {} is now '{}'", group.name(), field, value);
        LOGGER.info("Services already running keep their current settings.");
    }

    private static int whole(String value) {
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 0) {
                throw new IllegalArgumentException("'" + value + "' must not be negative");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("'" + value + "' is not a number");
        }
    }

    private static int positive(String value) {
        int parsed = whole(value);
        if (parsed == 0) {
            throw new IllegalArgumentException("'" + value + "' must be greater than zero");
        }
        return parsed;
    }

    private static int port(String value) {
        int parsed = whole(value);
        if (parsed < 1024 || parsed > 65000) {
            throw new IllegalArgumentException("A start port must be between 1024 and 65000");
        }
        return parsed;
    }

    private static boolean flag(String value) {
        return value.trim().toLowerCase(Locale.ROOT).matches("on|true|yes|1");
    }

    @Override
    public List<String> complete(String[] args) {
        if (args.length <= 1) {
            List<String> names = new ArrayList<>();
            groups.all().forEach(group -> names.add(group.name()));
            return names;
        }
        if (args.length == 2) {
            return FIELDS;
        }
        if (args.length == 3) {
            return switch (args[1].toLowerCase(Locale.ROOT)) {
                case "static", "fallback" -> List.of("on", "off");
                case "version", "build" -> List.of("latest");
                case "java" -> List.of("default");
                default -> List.of();
            };
        }
        return List.of();
    }
}
