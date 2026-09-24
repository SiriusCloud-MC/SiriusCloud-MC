package dev.sirius.cloud.api.permission;

import java.util.ArrayList;
import java.util.List;

/**
 * A named set of permissions, shared by everyone in it.
 *
 * <p>Passed over the wire as JSON, so this stays a plain mutable POJO with a
 * no-arg constructor, like every other model in this API.
 */
public final class PermissionGroup {

    private String name;

    /**
     * Which group's prefix wins when somebody is in several.
     *
     * <p>Higher takes precedence. Permissions themselves are merged rather than
     * ranked, because a player in two groups should hold both sets; only the
     * single visible prefix and suffix have to pick a winner.
     */
    private int priority;

    private String prefix = "";
    private String suffix = "";

    /**
     * Permission nodes. A leading {@code -} denies, which lets a group take
     * something away that an inherited group grants.
     */
    private List<String> permissions = new ArrayList<>();

    /** Groups this one inherits from, resolved recursively. */
    private List<String> inherits = new ArrayList<>();

    /**
     * Whether everyone is in this group without being added to it.
     *
     * <p>Exactly one group should carry this. It is what gives a brand new
     * player any permissions at all, and it is applied before any explicit
     * membership so an explicit group can override it.
     */
    private boolean defaultGroup;

    /** Required by the JSON codec. */
    @SuppressWarnings("unused")
    PermissionGroup() {
    }

    public PermissionGroup(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public int priority() {
        return priority;
    }

    public void priority(int priority) {
        this.priority = priority;
    }

    public String prefix() {
        return prefix == null ? "" : prefix;
    }

    public void prefix(String prefix) {
        this.prefix = prefix;
    }

    public String suffix() {
        return suffix == null ? "" : suffix;
    }

    public void suffix(String suffix) {
        this.suffix = suffix;
    }

    public List<String> permissions() {
        if (permissions == null) {
            permissions = new ArrayList<>();
        }
        return permissions;
    }

    public List<String> inherits() {
        if (inherits == null) {
            inherits = new ArrayList<>();
        }
        return inherits;
    }

    public boolean defaultGroup() {
        return defaultGroup;
    }

    public void defaultGroup(boolean defaultGroup) {
        this.defaultGroup = defaultGroup;
    }

    @Override
    public String toString() {
        return name + "{" + permissions().size() + " nodes, priority " + priority + "}";
    }
}
