package dev.sirius.cloud.module.permissions;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sirius.cloud.api.driver.CloudDriver;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.messaging.ChannelMessage;
import dev.sirius.cloud.api.module.CloudModule;
import dev.sirius.cloud.api.module.ModuleContext;
import dev.sirius.cloud.api.permission.PermissionChannels;
import dev.sirius.cloud.api.permission.PermissionDisplay;
import dev.sirius.cloud.api.permission.PermissionGroup;
import dev.sirius.cloud.api.permission.PermissionUser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Cloud-wide permissions, owned by the node.
 *
 * <p>The node holds the data and every server applies it, which is the
 * difference between this and installing a permissions plugin on each server:
 * a rank given here is in effect on servers that are empty right now and on
 * servers that have not started yet.
 *
 * <p>Changes travel as a whole {@link dev.sirius.cloud.api.permission.PermissionSnapshot}
 * rather than as deltas. The data is small, and a full replacement cannot drift
 * the way a missed delta can.
 */
public final class PermissionsModule implements CloudModule {

    private static final CloudLogger LOGGER = CloudLogger.of("Permissions");

    private static final Gson GSON = new Gson();
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private CloudDriver driver;
    private PermissionStore store;
    private PermissionDisplay display;
    private PermissionMutations mutations;

    @Override
    public void onEnable(ModuleContext context) {
        this.driver = context.driver();
        this.store = new PermissionStore(context.dataDirectory());

        try {
            this.display = loadDisplay(context.dataDirectory().resolve("config.json"));
            store.load();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read the permission files: "
                    + exception.getMessage());
        }

        this.mutations = new PermissionMutations(store);

        driver.messaging().subscribe(PermissionChannels.REQUEST, message -> publishSnapshot());
        driver.messaging().subscribe(PermissionChannels.MUTATE, this::onMutate);

        // Servers already running have not asked yet, and will not until
        // somebody joins them. Publishing once on enable means a module
        // reload does not leave them on stale data.
        publishSnapshot();

        LOGGER.info("Serving {} group(s) at revision {}",
                store.snapshot().groups().size(), store.revision());
        explainLuckPerms();
    }

    @Override
    public void onDisable() {
        LOGGER.info("Permissions are no longer being served");
    }

    /**
     * Applies one change and tells everyone.
     *
     * <p>Every mutation arrives from a server, which has already checked that
     * whoever typed it holds the permission to. The node does not re-check:
     * it cannot, since it has no notion of who is running the command beyond
     * what the server tells it. That is worth being explicit about, because it
     * means anything holding a service token can change permissions.
     */
    private void onMutate(ChannelMessage message) {
        JsonObject request;
        try {
            request = JsonParser.parseString(message.payload()).getAsJsonObject();
        } catch (RuntimeException exception) {
            return;
        }

        String requestId = string(request, "requestId");
        PermissionMutations.Result result;
        try {
            result = mutations.apply(request);
        } catch (RuntimeException exception) {
            result = PermissionMutations.Result.failure(
                    exception.getMessage() == null
                            ? exception.getClass().getSimpleName()
                            : exception.getMessage());
        }

        if (result.changed()) {
            store.commit();
            publishSnapshot();
            LOGGER.info("{} (by {}, now revision {})",
                    result.message(), message.sourceService(), store.revision());
        }

        if (!requestId.isBlank()) {
            JsonObject reply = new JsonObject();
            reply.addProperty("requestId", requestId);
            reply.addProperty("ok", result.ok());
            reply.addProperty("message", result.message());
            driver.messaging().publish(PermissionChannels.RESULT, GSON.toJson(reply));
        }
    }

    private void publishSnapshot() {
        driver.messaging()
                .publish(PermissionChannels.SNAPSHOT, GSON.toJson(store.snapshot().withDisplay(display)))
                .exceptionally(error -> {
                    LOGGER.debug("Could not publish the permission snapshot: {}", error.getMessage());
                    return null;
                });
    }

    /**
     * Chat format, nametags and tab list settings, written out with their
     * defaults on first start so there is a file to edit.
     */
    private static PermissionDisplay loadDisplay(Path file) throws IOException {
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                PermissionDisplay loaded = PRETTY.fromJson(reader, PermissionDisplay.class);
                if (loaded != null) {
                    return loaded;
                }
            }
        }
        PermissionDisplay defaults = new PermissionDisplay();
        Files.createDirectories(file.getParent());
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            PRETTY.toJson(defaults, writer);
        }
        return defaults;
    }

    /**
     * Says what these groups mean on a network that also runs LuckPerms,
     * since it is the first thing anybody mixing the two will wonder.
     */
    private void explainLuckPerms() {
        // The permissions plugin steps aside on servers that run LuckPerms,
        // and only shows its ranks there, so there is nothing to warn about -
        // but the groups below are then not what decides anything.
        LOGGER.info("On servers with LuckPerms, LuckPerms decides permissions and these groups are not used;"
                + " the display settings in config.json still apply.");
    }

    static String string(JsonObject body, String key) {
        return body.has(key) && !body.get(key).isJsonNull()
                ? body.get(key).getAsString().trim()
                : "";
    }

    /** Resolves a player by uuid if given, otherwise by last known name. */
    static Optional<PermissionUser> resolveUser(PermissionStore store, JsonObject body) {
        String rawId = string(body, "uuid");
        if (!rawId.isBlank()) {
            try {
                return store.findUser(UUID.fromString(rawId));
            } catch (IllegalArgumentException exception) {
                return Optional.empty();
            }
        }
        String name = string(body, "player");
        return name.isBlank() ? Optional.empty() : store.findUser(name);
    }

    static String normalise(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    static String describe(PermissionGroup group) {
        return group.name() + " (priority " + group.priority() + ")";
    }
}
