package dev.sirius.cloud.api.service;

import java.util.Locale;
import java.util.UUID;

/**
 * Identity of a service: a stable UUID plus the human-readable
 * {@code <group>-<ordinal>} name used in the console and in-game.
 *
 * @param uniqueId  never reused, the real identity
 * @param groupName the group this service was spawned from
 * @param ordinal   per-group counter, starting at 1
 */
public record ServiceId(UUID uniqueId, String groupName, int ordinal) {

    public ServiceId {
        if (uniqueId == null) {
            throw new IllegalArgumentException("uniqueId must not be null");
        }
        if (groupName == null || groupName.isBlank()) {
            throw new IllegalArgumentException("groupName must not be blank");
        }
        if (ordinal < 1) {
            throw new IllegalArgumentException("ordinal must be >= 1, got " + ordinal);
        }
    }

    public String name() {
        return groupName + "-" + ordinal;
    }

    /**
     * Canonical lookup key for this service's name.
     *
     * <p>Linux filesystems are case-sensitive and Windows filesystems are not.
     * Registry lookups are lowercased so that {@code Lobby-1} and {@code lobby-1}
     * resolve to the same service on both platforms rather than behaving
     * differently depending on where the node happens to run.
     */
    public String nameKey() {
        return name().toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return name();
    }
}
