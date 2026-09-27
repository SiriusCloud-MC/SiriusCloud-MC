package dev.sirius.cloud.api.network;

import java.util.Optional;
import java.util.UUID;

/** Whoever ran a {@link NetworkCommand}: a player on some proxy, or the node console. */
public interface CommandSender {

    String name();

    /** Empty for the console. */
    Optional<UUID> uniqueId();

    boolean isConsole();

    /**
     * Whether the sender holds a permission.
     *
     * <p>The console holds everything. A player's answer comes from their proxy,
     * which evaluated the command's {@link NetworkCommand#permission()} and
     * {@link NetworkCommand#extraPermissions()} when they ran it - any other
     * permission reads as not held.
     */
    boolean hasPermission(String permission);

    /** A reply, in MiniMessage. User-supplied text must be escaped with {@link Text#escape}. */
    void sendMessage(String miniMessage);
}
