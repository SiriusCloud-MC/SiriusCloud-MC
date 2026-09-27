package dev.sirius.cloud.driver.migration;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.driver.config.JsonConfig;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Brings an install's files up to date with this build, once, on startup.
 *
 * <p>The install records its data version in {@code local/data-version.json}.
 * On startup every migration above it runs in order, each recorded as soon as
 * it succeeds, so an interrupted run resumes where it stopped instead of
 * starting over. Before a migration changes a file the original is copied to
 * {@code local/migration-backups/}; if it fails, its files are restored, the
 * version stays where it was, and startup stops with the reason.
 *
 * <p>An install with no record is either brand new - nothing to migrate, so it
 * is stamped with the current version - or older than this system, in which
 * case every migration applies.
 *
 * <p>Data newer than this build refuses to start. An older build rewriting
 * files it only half understands would quietly drop whatever the newer one
 * added, and that is not recoverable after the fact.
 */
public final class Migrator {

    /** Set to {@code true} to start an older build on newer data anyway. */
    public static final String ALLOW_DOWNGRADE = "siriuscloud.allowDowngrade";

    private static final CloudLogger LOGGER = CloudLogger.of("Migration");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final Path root;
    private final List<Migration> migrations;

    public Migrator(Path root, List<Migration> migrations) {
        this.root = root.toAbsolutePath().normalize();
        this.migrations = new ArrayList<>(migrations);
        this.migrations.sort(Comparator.comparingInt(Migration::version));
        for (int index = 0; index < this.migrations.size(); index++) {
            Migration migration = this.migrations.get(index);
            if (migration.version() < 1) {
                throw new IllegalArgumentException("Migration versions start at 1: " + migration.description());
            }
            if (index > 0 && this.migrations.get(index - 1).version() == migration.version()) {
                throw new IllegalArgumentException("Two migrations share version " + migration.version());
            }
        }
    }

    /** The highest version this build knows, which a fully migrated install is at. */
    public int latest() {
        return migrations.isEmpty() ? 0 : migrations.getLast().version();
    }

    /** The install's recorded version, or -1 if it has no record yet. */
    public int current() throws IOException {
        JsonObject state = readState();
        return state == null ? -1 : state.get("dataVersion").getAsInt();
    }

    /** Everything applied so far, oldest first, as recorded. */
    public JsonArray history() throws IOException {
        JsonObject state = readState();
        return state == null || !state.has("history") ? new JsonArray() : state.getAsJsonArray("history");
    }

    public Path backups() {
        return root.resolve("local").resolve("migration-backups");
    }

    /**
     * Runs whatever is pending.
     *
     * @param freshInstall whether this is the first start ever, which no file
     *                     here can say on its own: the caller knows what an
     *                     install that has been used before looks like
     */
    public void run(boolean freshInstall) throws IOException {
        JsonObject state = readState();
        if (state == null) {
            if (freshInstall) {
                writeState(newState(latest()));
                return;
            }
            // Predates migrations: everything since then applies.
            state = newState(0);
        }

        int current = state.get("dataVersion").getAsInt();
        if (current > latest()) {
            String message = "This data was written by a newer SiriusCloud (data version " + current
                    + ", this build knows up to " + latest() + "). Run the newer version, or restore a backup"
                    + " from " + backups() + ".";
            if (!Boolean.getBoolean(ALLOW_DOWNGRADE)) {
                throw new MigrationException(message + " To start anyway, add -D" + ALLOW_DOWNGRADE + "=true.");
            }
            LOGGER.warn("{} Starting anyway, because {} is set.", message, ALLOW_DOWNGRADE);
            return;
        }

        List<Migration> pending = migrations.stream().filter(migration -> migration.version() > current).toList();
        if (pending.isEmpty()) {
            if (readState() == null) {
                writeState(state);
            }
            return;
        }

        Path runBackup = backups().resolve(LocalDateTime.now().format(STAMP) + "-from-v" + current);
        LOGGER.info("Updating this install's files: {} migration(s), from version {} to {}",
                pending.size(), current, latest());

        for (Migration migration : pending) {
            MigrationContext context = new MigrationContext(root, runBackup.resolve("v" + migration.version()));
            try {
                migration.apply(context);
            } catch (IOException | RuntimeException exception) {
                try {
                    context.rollBack();
                } catch (IOException rollback) {
                    exception.addSuppressed(rollback);
                    throw new MigrationException("Migration " + migration.version() + " (" + migration.description()
                            + ") failed and could not be undone: " + rollback.getMessage()
                            + ". The originals are in " + runBackup + ".", exception);
                }
                throw new MigrationException("Migration " + migration.version() + " (" + migration.description()
                        + ") failed, and its changes were undone: " + exception.getMessage(), exception);
            }

            JsonObject entry = new JsonObject();
            entry.addProperty("version", migration.version());
            entry.addProperty("description", migration.description());
            entry.addProperty("appliedAt", System.currentTimeMillis());
            JsonArray changes = new JsonArray();
            context.changes().forEach(changes::add);
            entry.add("changes", changes);
            state.getAsJsonArray("history").add(entry);
            state.addProperty("dataVersion", migration.version());
            // After every step, so a crash mid-run resumes rather than repeats.
            writeState(state);

            if (context.changes().isEmpty()) {
                LOGGER.info("  {}. {}: nothing to change", migration.version(), migration.description());
            } else {
                LOGGER.info("  {}. {}", migration.version(), migration.description());
                context.changes().forEach(change -> LOGGER.info("       {}", change));
            }
        }
        if (Files.exists(runBackup)) {
            LOGGER.info("The original files are in {}", root.relativize(runBackup));
        }
    }

    private static JsonObject newState(int version) {
        JsonObject state = new JsonObject();
        state.addProperty("dataVersion", version);
        state.add("history", new JsonArray());
        return state;
    }

    private Path stateFile() {
        return root.resolve("local").resolve("data-version.json");
    }

    private JsonObject readState() throws IOException {
        Path file = stateFile();
        if (Files.notExists(file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject state = JsonParser.parseReader(reader).getAsJsonObject();
            if (!state.has("dataVersion")) {
                throw new MigrationException(file + " has no dataVersion. Restore it, or delete it to re-run"
                        + " every migration.");
            }
            if (!state.has("history")) {
                state.add("history", new JsonArray());
            }
            return state;
        } catch (RuntimeException exception) {
            throw new MigrationException(file + " is unreadable (" + exception.getMessage() + "). Restore it,"
                    + " or delete it to re-run every migration.", exception);
        }
    }

    private void writeState(JsonObject state) throws IOException {
        JsonConfig.save(stateFile(), state);
    }
}
