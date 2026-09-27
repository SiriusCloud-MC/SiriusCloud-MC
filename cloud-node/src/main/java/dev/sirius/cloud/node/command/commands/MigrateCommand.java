package dev.sirius.cloud.node.command.commands;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.driver.migration.Migrator;
import dev.sirius.cloud.node.command.Command;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Where this install's files stand: their data version, what has been
 * migrated and when, and where the originals were kept.
 *
 * <p>Migrations themselves run on startup, before anything reads a file, so
 * there is nothing here to trigger: updating is replacing the jars and
 * starting again.
 */
public final class MigrateCommand implements Command {

    private static final CloudLogger LOGGER = CloudLogger.of("Console");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final Migrator migrator;

    public MigrateCommand(Migrator migrator) {
        this.migrator = migrator;
    }

    @Override
    public String name() {
        return "migrate";
    }

    @Override
    public String description() {
        return "Shows this install's data version and migration history";
    }

    @Override
    public void execute(String[] args) {
        try {
            int current = migrator.current();
            CloudLogger.raw("Data version : " + current + " (this build: " + migrator.latest() + ")");
            CloudLogger.raw(current >= migrator.latest()
                    ? "Status       : up to date"
                    : "Status       : restart the node to apply " + (migrator.latest() - current) + " migration(s)");

            var history = migrator.history();
            if (history.isEmpty()) {
                CloudLogger.raw("History      : nothing migrated; this install started on a current version");
            } else {
                CloudLogger.raw("History      :");
                for (JsonElement element : history) {
                    JsonObject entry = element.getAsJsonObject();
                    CloudLogger.raw("  " + entry.get("version").getAsInt() + ". "
                            + entry.get("description").getAsString() + "  ("
                            + DATE.format(Instant.ofEpochMilli(entry.get("appliedAt").getAsLong())) + ")");
                    if (entry.has("changes")) {
                        entry.getAsJsonArray("changes").forEach(change ->
                                CloudLogger.raw("       " + change.getAsString()));
                    }
                }
            }
            CloudLogger.raw("Backups      : " + migrator.backups());
            CloudLogger.raw("Updating is: stop the node, replace the jars, start it. Migrations run on start.");
        } catch (IOException exception) {
            LOGGER.warn("Could not read the migration state: {}", exception.getMessage());
        }
    }
}
