package dev.sirius.cloud.node.command;

import dev.sirius.cloud.api.logging.CloudLogger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Registry and dispatcher for console commands. */
public final class CommandManager {

    private static final CloudLogger LOGGER = CloudLogger.of("Console");

    /** Insertion-ordered so {@code help} lists commands in a deliberate order. */
    private final Map<String, Command> commands = new LinkedHashMap<>();
    private final List<Command> registered = new ArrayList<>();

    public void register(Command command) {
        commands.put(command.name().toLowerCase(Locale.ROOT), command);
        command.aliases().forEach(alias -> commands.put(alias.toLowerCase(Locale.ROOT), command));
        registered.add(command);
    }

    public Optional<Command> find(String name) {
        return Optional.ofNullable(commands.get(name.toLowerCase(Locale.ROOT)));
    }

    public Collection<Command> all() {
        return List.copyOf(registered);
    }

    public Collection<String> names() {
        return List.copyOf(commands.keySet());
    }

    public void dispatch(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return;
        }

        Optional<Command> command = find(parts[0]);
        if (command.isEmpty()) {
            LOGGER.warn("Unknown command '{}'. Type 'help' for a list.", parts[0]);
            return;
        }

        String[] args = new String[parts.length - 1];
        System.arraycopy(parts, 1, args, 0, args.length);

        try {
            command.get().execute(args);
        } catch (Exception exception) {
            // A broken command must never take the console down with it.
            LOGGER.error("Command '" + parts[0] + "' failed", exception);
        }
    }
}
