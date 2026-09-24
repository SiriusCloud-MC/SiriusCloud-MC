package dev.sirius.cloud.module.permissions;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.permission.PermissionGroup;
import dev.sirius.cloud.api.permission.PermissionSnapshot;
import dev.sirius.cloud.api.permission.PermissionUser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The permission data, and the only thing that writes it.
 *
 * <p>Two JSON files rather than a database: this is a few kilobytes that an
 * operator should be able to read, diff and put in version control, and a
 * cloud that needs a database running before anyone can log in has bought a
 * dependency for nothing.
 *
 * <p>Every mutation bumps {@link #revision()} and persists immediately. A crash
 * between the change and the next snapshot must not lose the change.
 */
final class PermissionStore {

    private static final CloudLogger LOGGER = CloudLogger.of("Permissions");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path groupsFile;
    private final Path usersFile;

    /** Keyed by lowercased name, so lookups are not case-sensitive. */
    private final Map<String, PermissionGroup> groups = new ConcurrentHashMap<>();
    private final Map<UUID, PermissionUser> users = new ConcurrentHashMap<>();

    /**
     * Monotonic version of the data, in milliseconds.
     *
     * <p>Wall-clock rather than a counter, and that is not cosmetic: servers
     * ignore a snapshot older than the one they hold, and a counter restarting
     * at zero when the node restarts would have every long-lived server reject
     * the new node's data forever. Clamped to always advance, so two commits in
     * the same millisecond still order correctly.
     */
    private final AtomicLong revision = new AtomicLong(System.currentTimeMillis());

    PermissionStore(Path directory) {
        this.groupsFile = directory.resolve("groups.json");
        this.usersFile = directory.resolve("users.json");
    }

    long revision() {
        return revision.get();
    }

    synchronized void load() throws IOException {
        List<PermissionGroup> loadedGroups = read(groupsFile, new TypeToken<>() {
        });
        List<PermissionUser> loadedUsers = read(usersFile, new TypeToken<>() {
        });

        loadedGroups.forEach(group -> groups.put(key(group.name()), group));
        loadedUsers.forEach(user -> {
            if (user.uniqueId() != null) {
                users.put(user.uniqueId(), user);
            }
        });

        if (groups.isEmpty()) {
            // A permissions system with no groups grants nothing to anybody and
            // looks broken. Two sensible ones beat an empty file and a wiki page.
            createStarterGroups();
            save();
            LOGGER.info("No groups configured, created 'default' and 'admin'");
        }

        LOGGER.info("Loaded {} group(s) and {} user(s)", groups.size(), users.size());
    }

    private void createStarterGroups() {
        PermissionGroup def = new PermissionGroup("default");
        def.defaultGroup(true);
        def.priority(0);
        def.prefix("");
        groups.put(key(def.name()), def);

        PermissionGroup admin = new PermissionGroup("admin");
        admin.priority(100);
        admin.prefix("&c[Admin] ");
        admin.inherits().add("default");
        admin.permissions().add("*");
        groups.put(key(admin.name()), admin);
    }

    synchronized PermissionSnapshot snapshot() {
        return new PermissionSnapshot(
                revision.get(),
                new ArrayList<>(groups.values()),
                new ArrayList<>(users.values()));
    }

    Optional<PermissionGroup> group(String name) {
        return Optional.ofNullable(groups.get(key(name)));
    }

    synchronized PermissionGroup createGroup(String name) {
        PermissionGroup group = new PermissionGroup(name);
        groups.put(key(name), group);
        return group;
    }

    synchronized boolean deleteGroup(String name) {
        PermissionGroup removed = groups.remove(key(name));
        if (removed == null) {
            return false;
        }
        // Leaving memberships behind would resurrect the group the moment
        // somebody recreated the name, handing it to everyone who used to be
        // in it. Dropping the references here is the honest cleanup.
        users.values().forEach(user ->
                user.groups().removeIf(held -> held.equalsIgnoreCase(name)));
        groups.values().forEach(group ->
                group.inherits().removeIf(parent -> parent.equalsIgnoreCase(name)));
        return true;
    }

    /** The user record, created on first use. */
    synchronized PermissionUser user(UUID uniqueId, String name) {
        PermissionUser user = users.computeIfAbsent(uniqueId,
                id -> new PermissionUser(id, name == null ? "" : name));
        if (name != null && !name.isBlank()) {
            user.name(name);
        }
        return user;
    }

    Optional<PermissionUser> findUser(UUID uniqueId) {
        return Optional.ofNullable(users.get(uniqueId));
    }

    /** Looks a player up by their last known name, for commands typed by hand. */
    Optional<PermissionUser> findUser(String name) {
        return users.values().stream()
                .filter(user -> user.name().equalsIgnoreCase(name))
                .findFirst();
    }

    /** Records that something changed and writes it to disk. */
    synchronized void commit() {
        revision.updateAndGet(previous -> Math.max(previous + 1, System.currentTimeMillis()));
        try {
            save();
        } catch (IOException exception) {
            LOGGER.error("Could not persist permissions; the change is live but "
                    + "will be lost on restart", exception);
        }
    }

    private synchronized void save() throws IOException {
        write(groupsFile, new ArrayList<>(groups.values()));
        write(usersFile, new ArrayList<>(users.values()));
    }

    private static <T> List<T> read(Path path, TypeToken<List<T>> type) throws IOException {
        if (!Files.exists(path)) {
            return new ArrayList<>();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            List<T> value = GSON.fromJson(reader, type.getType());
            return value == null ? new ArrayList<>() : value;
        }
    }

    /** Writes via a temporary file, so a crash mid-write cannot truncate it. */
    private static void write(Path path, Object value) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            GSON.toJson(value, writer);
        }
        try {
            Files.move(temporary, path,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
