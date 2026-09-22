package dev.sirius.cloud.node.command;

import java.util.List;

/** One console command. */
public interface Command {

    String name();

    String description();

    /** Argument syntax, shown by {@code help}. */
    default String usage() {
        return name();
    }

    default List<String> aliases() {
        return List.of();
    }

    void execute(String[] args);

    /**
     * Tab-completion candidates for the argument currently being typed.
     *
     * @param args arguments so far, excluding the command name
     */
    default List<String> complete(String[] args) {
        return List.of();
    }
}
