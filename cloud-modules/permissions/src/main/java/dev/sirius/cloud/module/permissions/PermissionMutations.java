package dev.sirius.cloud.module.permissions;

import com.google.gson.JsonObject;
import dev.sirius.cloud.api.permission.PermissionGroup;
import dev.sirius.cloud.api.permission.PermissionUser;

import java.util.Optional;
import java.util.UUID;

/**
 * Every change the permission data accepts, in one place.
 *
 * <p>Separate from the module so the set of operations is readable on its own,
 * and so adding one is a single case rather than a new packet, a new handler
 * and a new command.
 */
final class PermissionMutations {

    private final PermissionStore store;

    PermissionMutations(PermissionStore store) {
        this.store = store;
    }

    /**
     * The outcome of a mutation.
     *
     * @param ok      whether it was accepted
     * @param changed whether anything actually differs now, which is what
     *                decides if a snapshot goes out. "Already in that group"
     *                is a success that changes nothing.
     * @param message what to tell whoever asked
     */
    record Result(boolean ok, boolean changed, String message) {

        static Result changedTo(String message) {
            return new Result(true, true, message);
        }

        static Result noChange(String message) {
            return new Result(true, false, message);
        }

        static Result failure(String message) {
            return new Result(false, false, message);
        }
    }

    Result apply(JsonObject request) {
        String operation = PermissionsModule.string(request, "op");

        return switch (operation) {
            case "group-create" -> groupCreate(request);
            case "group-delete" -> groupDelete(request);
            case "group-set" -> groupSet(request);
            case "group-perm" -> groupPermission(request);
            case "group-inherit" -> groupInherit(request);
            case "user-group" -> userGroup(request);
            case "user-perm" -> userPermission(request);
            default -> Result.failure("Unknown operation '" + operation + "'");
        };
    }

    // ---------------------------------------------------------------- groups

    private Result groupCreate(JsonObject request) {
        String name = PermissionsModule.string(request, "group");
        if (name.isBlank()) {
            return Result.failure("A group needs a name");
        }
        if (store.group(name).isPresent()) {
            return Result.noChange("A group called '" + name + "' already exists");
        }
        store.createGroup(name);
        return Result.changedTo("Created group " + name);
    }

    private Result groupDelete(JsonObject request) {
        String name = PermissionsModule.string(request, "group");
        Optional<PermissionGroup> group = store.group(name);
        if (group.isEmpty()) {
            return Result.failure("No group called '" + name + "'");
        }
        if (group.get().defaultGroup()) {
            // Without a default group a new player holds nothing at all, which
            // looks like the permission system having failed rather than a
            // deliberate deletion.
            return Result.failure("'" + name + "' is the default group; make another "
                    + "group the default before deleting it");
        }
        store.deleteGroup(name);
        return Result.changedTo("Deleted group " + name);
    }

    private Result groupSet(JsonObject request) {
        String name = PermissionsModule.string(request, "group");
        Optional<PermissionGroup> found = store.group(name);
        if (found.isEmpty()) {
            return Result.failure("No group called '" + name + "'");
        }
        PermissionGroup group = found.get();
        String field = PermissionsModule.string(request, "field");
        String value = PermissionsModule.string(request, "value");

        switch (field) {
            case "priority" -> {
                try {
                    group.priority(Integer.parseInt(value));
                } catch (NumberFormatException exception) {
                    return Result.failure("'" + value + "' is not a number");
                }
            }
            case "prefix" -> group.prefix(value);
            case "suffix" -> group.suffix(value);
            case "default" -> {
                boolean makeDefault = Boolean.parseBoolean(value);
                if (makeDefault) {
                    // Exactly one default, or resolution would depend on map
                    // ordering and quietly differ between servers.
                    store.snapshot().groups().forEach(other -> other.defaultGroup(false));
                }
                group.defaultGroup(makeDefault);
            }
            default -> {
                return Result.failure("Unknown field '" + field
                        + "'. Try priority, prefix, suffix or default.");
            }
        }
        return Result.changedTo("Set " + field + " of " + group.name() + " to '" + value + "'");
    }

    private Result groupPermission(JsonObject request) {
        Optional<PermissionGroup> found = store.group(PermissionsModule.string(request, "group"));
        if (found.isEmpty()) {
            return Result.failure("No such group");
        }
        PermissionGroup group = found.get();
        String node = PermissionsModule.normalise(PermissionsModule.string(request, "node"));
        if (node.isBlank()) {
            return Result.failure("A permission node is required");
        }

        if (PermissionsModule.string(request, "action").equals("remove")) {
            boolean removed = group.permissions().removeIf(held -> held.equalsIgnoreCase(node));
            return removed
                    ? Result.changedTo("Removed " + node + " from " + group.name())
                    : Result.noChange(group.name() + " does not have " + node);
        }

        if (group.permissions().stream().anyMatch(held -> held.equalsIgnoreCase(node))) {
            return Result.noChange(group.name() + " already has " + node);
        }
        group.permissions().add(node);
        return Result.changedTo("Added " + node + " to " + group.name());
    }

    private Result groupInherit(JsonObject request) {
        Optional<PermissionGroup> found = store.group(PermissionsModule.string(request, "group"));
        if (found.isEmpty()) {
            return Result.failure("No such group");
        }
        PermissionGroup group = found.get();
        String parent = PermissionsModule.string(request, "parent");
        if (store.group(parent).isEmpty()) {
            return Result.failure("No group called '" + parent + "' to inherit from");
        }
        if (parent.equalsIgnoreCase(group.name())) {
            return Result.failure("A group cannot inherit from itself");
        }

        if (PermissionsModule.string(request, "action").equals("remove")) {
            boolean removed = group.inherits().removeIf(held -> held.equalsIgnoreCase(parent));
            return removed
                    ? Result.changedTo(group.name() + " no longer inherits " + parent)
                    : Result.noChange(group.name() + " does not inherit " + parent);
        }

        if (group.inherits().stream().anyMatch(held -> held.equalsIgnoreCase(parent))) {
            return Result.noChange(group.name() + " already inherits " + parent);
        }
        group.inherits().add(parent);
        return Result.changedTo(group.name() + " now inherits " + parent);
    }

    // ----------------------------------------------------------------- users

    private Result userGroup(JsonObject request) {
        String rawId = PermissionsModule.string(request, "uuid");
        String name = PermissionsModule.string(request, "player");
        String groupName = PermissionsModule.string(request, "group");

        Optional<PermissionGroup> group = store.group(groupName);
        if (group.isEmpty()) {
            return Result.failure("No group called '" + groupName + "'");
        }

        boolean removing = PermissionsModule.string(request, "action").equals("remove");

        PermissionUser user;
        if (!rawId.isBlank()) {
            UUID uniqueId;
            try {
                uniqueId = UUID.fromString(rawId);
            } catch (IllegalArgumentException exception) {
                return Result.failure("'" + rawId + "' is not a valid UUID");
            }
            // Created on add: a player who has never had a rank has no record
            // yet, and giving them one is exactly when it should appear.
            user = removing
                    ? store.findUser(uniqueId).orElse(null)
                    : store.user(uniqueId, name);
        } else {
            user = store.findUser(name).orElse(null);
            if (user == null && !removing) {
                return Result.failure("The cloud has never seen a player called '" + name
                        + "'. Use their UUID, or have them join once.");
            }
        }

        if (user == null) {
            return Result.noChange("That player has no permission record");
        }

        if (removing) {
            boolean removed = user.groups().removeIf(held -> held.equalsIgnoreCase(groupName));
            return removed
                    ? Result.changedTo("Removed " + user.name() + " from " + groupName)
                    : Result.noChange(user.name() + " is not in " + groupName);
        }

        if (user.groups().stream().anyMatch(held -> held.equalsIgnoreCase(groupName))) {
            return Result.noChange(user.name() + " is already in " + groupName);
        }
        user.groups().add(group.get().name());
        return Result.changedTo("Added " + user.name() + " to " + groupName);
    }

    private Result userPermission(JsonObject request) {
        Optional<PermissionUser> found = PermissionsModule.resolveUser(store, request);
        String node = PermissionsModule.normalise(PermissionsModule.string(request, "node"));
        if (node.isBlank()) {
            return Result.failure("A permission node is required");
        }

        boolean removing = PermissionsModule.string(request, "action").equals("remove");

        PermissionUser user;
        if (found.isPresent()) {
            user = found.get();
        } else if (removing) {
            return Result.noChange("That player has no permission record");
        } else {
            String rawId = PermissionsModule.string(request, "uuid");
            if (rawId.isBlank()) {
                return Result.failure("The cloud has never seen that player. Use their UUID.");
            }
            try {
                user = store.user(UUID.fromString(rawId), PermissionsModule.string(request, "player"));
            } catch (IllegalArgumentException exception) {
                return Result.failure("'" + rawId + "' is not a valid UUID");
            }
        }

        if (removing) {
            boolean removed = user.permissions().removeIf(held -> held.equalsIgnoreCase(node));
            return removed
                    ? Result.changedTo("Removed " + node + " from " + user.name())
                    : Result.noChange(user.name() + " does not have " + node);
        }

        if (user.permissions().stream().anyMatch(held -> held.equalsIgnoreCase(node))) {
            return Result.noChange(user.name() + " already has " + node);
        }
        user.permissions().add(node);
        return Result.changedTo("Added " + node + " to " + user.name());
    }
}
