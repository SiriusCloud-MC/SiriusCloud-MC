package dev.sirius.cloud.api.driver;

import dev.sirius.cloud.api.player.CloudPlayer;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Everything you can do to players, from anywhere in the cloud.
 *
 * <p>The point of this living behind {@link CloudDriver} is that a plugin on
 * one lobby can move, message or kick a player who is on a different server
 * behind a different proxy, without knowing that any of that is true.
 */
public interface PlayerProvider {

    CompletableFuture<Collection<CloudPlayer>> onlinePlayers();

    /** Players currently on one service, by its name. */
    CompletableFuture<Collection<CloudPlayer>> playersOn(String serviceName);

    Optional<CloudPlayer> cachedPlayer(UUID uniqueId);

    Optional<CloudPlayer> cachedPlayer(String name);

    int onlineCount();

    /** Moves a player to a named service. */
    CompletableFuture<Void> connect(UUID uniqueId, String serviceName);

    /**
     * Moves a player to any service of a group.
     *
     * <p>The node picks the least-loaded one, so callers do not have to know
     * which instances exist or repeat the balancing themselves.
     */
    CompletableFuture<Void> connectToGroup(UUID uniqueId, String groupName);

    CompletableFuture<Void> sendMessage(UUID uniqueId, String message);

    /** Message to everyone online, across every proxy. */
    CompletableFuture<Void> broadcast(String message);

    CompletableFuture<Void> kick(UUID uniqueId, String reason);
}
