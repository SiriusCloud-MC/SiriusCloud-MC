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

    /**
     * What an unknown command falls through to.
     *
     * <p>The network commands modules register - {@code ban}, {@code party} -
     * run here too, as the console, so the node is never the one place an
     * operator cannot use them.
     */
    public interface Fallback {
        boolean dispatch(String name, String[] args);

        Map<String, String> describe();

        List<String> complete(String name, String[] args);
    }

    private volatile Fallback fallback;

    public void fallback(Fallback fallback) {
        this.fallback = fallback;
    }

    public Optional<Fallback> fallback() {
        return Optional.ofNullable(fallback);
    }

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

        String[] args = new String[parts.length - 1];
        System.arraycopy(parts, 1, args, 0, args.length);

        Optional<Command> command = find(parts[0]);
        if (command.isEmpty()) {
            Fallback current = fallback;
            if (current == null || !current.dispatch(parts[0], args)) {
                LOGGER.warn("Unknown command '{}'. Type 'help' for a list.", parts[0]);
            }
            return;
        }

        try {
            command.get().execute(args);
        } catch (Exception exception) {
            // A broken command must never take the console down with it.
            LOGGER.error("Command '" + parts[0] + "' failed", exception);
        }
    }
}
