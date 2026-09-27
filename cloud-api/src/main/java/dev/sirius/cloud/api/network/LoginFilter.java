package dev.sirius.cloud.api.network;

import java.util.List;
import java.util.Optional;

/**
 * A check every login passes through before the player reaches a server.
 *
 * <p>Runs on a virtual thread and may block. It is bounded all the same: a proxy
 * waits a few seconds for the answer and then lets the player in, because a
 * slow or unreachable node must not lock every player out of the network.
 */
@FunctionalInterface
public interface LoginFilter {

    /** @return a reason to refuse, in MiniMessage, or empty to allow */
    Optional<String> check(LoginAttempt attempt);

    /** Permissions the proxy should evaluate so {@link LoginAttempt#hasPermission} can answer them. */
    default List<String> permissions() {
        return List.of();
    }
}
