package dev.sirius.cloud.api.network;

import java.util.List;

/**
 * A command every proxy exposes, executed on the node.
 *
 * <p>The proxy checks {@link #permission()} before forwarding, because only it
 * knows what a player may do. Anything the command needs to test beyond that
 * goes in {@link #extraPermissions()}: the proxy evaluates those too and sends
 * the answers along, so {@link CommandSender#hasPermission} works on the node.
 *
 * <p>{@link #execute} runs on a virtual thread, so it may block on the database.
 */
public interface NetworkCommand {

    String name();

    default List<String> aliases() {
        return List.of();
    }

    /** Needed to see and run the command at all; null means everyone. */
    default String permission() {
        return null;
    }

    default String description() {
        return "";
    }

    /** Further permissions {@link CommandSender#hasPermission} must be able to answer. */
    default List<String> extraPermissions() {
        return List.of();
    }

    void execute(CommandSender sender, String[] args);

    /** Tab completions for the argument being typed. Must be quick; slow answers are dropped. */
    default List<String> suggest(CommandSender sender, String[] args) {
        return List.of();
    }
}
