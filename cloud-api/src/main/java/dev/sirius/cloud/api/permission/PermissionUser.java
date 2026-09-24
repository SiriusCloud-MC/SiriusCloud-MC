package dev.sirius.cloud.api.permission;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One player's group memberships and their own permission nodes. */
public final class PermissionUser {

    private UUID uniqueId;

    /**
     * Last known name, for display only.
     *
     * <p>Never used for lookups: names change and UUIDs do not, and a
     * permissions system that keys on names hands somebody else's rank to
     * whoever claims a freed name.
     */
    private String name = "";

    private List<String> groups = new ArrayList<>();
    private List<String> permissions = new ArrayList<>();

    /** Required by the JSON codec. */
    @SuppressWarnings("unused")
    PermissionUser() {
    }

    public PermissionUser(UUID uniqueId, String name) {
        this.uniqueId = uniqueId;
        this.name = name;
    }

    public UUID uniqueId() {
        return uniqueId;
    }

    public String name() {
        return name == null ? "" : name;
    }

    public void name(String name) {
        this.name = name;
    }

    public List<String> groups() {
        if (groups == null) {
            groups = new ArrayList<>();
        }
        return groups;
    }

    public List<String> permissions() {
        if (permissions == null) {
            permissions = new ArrayList<>();
        }
        return permissions;
    }

    @Override
    public String toString() {
        return name() + "{" + groups() + "}";
    }
}
