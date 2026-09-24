package dev.sirius.cloud.api.permission;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The complete permission state, as published to every server.
 *
 * <p>Sent whole rather than as deltas. The data is small — groups, plus only
 * those players who have been given something explicitly, not every player who
 * has ever joined — and a full replacement cannot drift out of sync the way a
 * missed delta can. A server that reconnects after an outage is correct
 * immediately instead of correct-ish until the next change.
 *
 * <p>{@link #revision} exists so a receiver can ignore a snapshot older than
 * the one it already has, which matters because delivery is unordered.
 */
public final class PermissionSnapshot {

    private long revision;
    private List<PermissionGroup> groups = new ArrayList<>();
    private List<PermissionUser> users = new ArrayList<>();

    /** Required by the JSON codec. */
    @SuppressWarnings("unused")
    PermissionSnapshot() {
    }

    public PermissionSnapshot(long revision, List<PermissionGroup> groups, List<PermissionUser> users) {
        this.revision = revision;
        this.groups = groups;
        this.users = users;
    }

    public long revision() {
        return revision;
    }

    public List<PermissionGroup> groups() {
        return groups == null ? List.of() : groups;
    }

    public List<PermissionUser> users() {
        return users == null ? List.of() : users;
    }

    public Optional<PermissionGroup> group(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        return groups().stream()
                .filter(group -> group.name().toLowerCase(Locale.ROOT).equals(key))
                .findFirst();
    }

    public Optional<PermissionUser> user(UUID uniqueId) {
        return users().stream()
                .filter(user -> uniqueId.equals(user.uniqueId()))
                .findFirst();
    }

    /** The group everyone is in without being added to it, if one is marked. */
    public Optional<PermissionGroup> defaultGroup() {
        return groups().stream().filter(PermissionGroup::defaultGroup).findFirst();
    }
}
