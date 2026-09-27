package dev.sirius.cloud.api.network;

import java.util.Map;
import java.util.UUID;

/**
 * A player trying to join, as a {@link LoginFilter} sees them.
 *
 * @param permissions answers for the permissions filters declared; anything
 *                    else reads as not held
 */
public record LoginAttempt(UUID uniqueId, String name, String address, String proxyName,
                           Map<String, Boolean> permissions) {

    public boolean hasPermission(String permission) {
        return Boolean.TRUE.equals(permissions.get(permission));
    }
}
