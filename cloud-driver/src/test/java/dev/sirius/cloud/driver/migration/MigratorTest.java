package dev.sirius.cloud.driver.migration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigratorTest {

    @TempDir
    Path root;

    private final List<Integer> ran = new ArrayList<>();

    /** Sets {@code field} in config.json to its version, or throws after writing if told to. */
    private Migration step(int version, boolean fail) {
        return new Migration() {
            @Override
            public int version() {
                return version;
            }

            @Override
            public String description() {
                return "step " + version;
            }

            @Override
            public void apply(MigrationContext context) throws IOException {
                ran.add(version);
                JsonObject config = context.readJson("config.json").orElseThrow().getAsJsonObject();
                config.addProperty("field", version);
                context.writeJson("config.json", config);
                context.writeJson("created.json", new JsonObject());
                context.note("field -> " + version);
                if (fail) {
                    throw new IOException("boom");
                }
            }
        };
    }

    private void config(int field) throws IOException {
        Files.writeString(root.resolve("config.json"), "{\"field\":" + field + "}", StandardCharsets.UTF_8);
    }

    private int field() throws IOException {
        return JsonParser.parseString(Files.readString(root.resolve("config.json"))).getAsJsonObject()
                .get("field").getAsInt();
    }

    @Test
    void aFreshInstallIsStampedWithoutRunningAnything() throws IOException {
        Migrator migrator = new Migrator(root, List.of(step(1, false), step(2, false)));
        migrator.run(true);
        assertTrue(ran.isEmpty());
        assertEquals(2, migrator.current());
    }

    @Test
    void anInstallFromBeforeMigrationsGetsThemAllInOrder() throws IOException {
        config(0);
        Migrator migrator = new Migrator(root, List.of(step(2, false), step(1, false)));
        migrator.run(false);
        assertEquals(List.of(1, 2), ran);
        assertEquals(2, field());
        assertEquals(2, migrator.current());
        assertEquals(2, migrator.history().size());
    }

    @Test
    void onlyPendingMigrationsRun() throws IOException {
        config(0);
        new Migrator(root, List.of(step(1, false))).run(false);
        ran.clear();
        new Migrator(root, List.of(step(1, false), step(2, false))).run(false);
        assertEquals(List.of(2), ran);

        ran.clear();
        new Migrator(root, List.of(step(1, false), step(2, false))).run(false);
        assertTrue(ran.isEmpty());
    }

    @Test
    void aFailedMigrationIsUndoneAndNotRecorded() throws IOException {
        config(0);
        Migrator migrator = new Migrator(root, List.of(step(1, false), step(2, true)));
        assertThrows(MigrationException.class, () -> migrator.run(false));

        // Step 1 stands, step 2's writes are rolled back, and the record says 1.
        assertEquals(1, field());
        assertEquals(1, migrator.current());
        assertTrue(Files.exists(root.resolve("created.json")), "created by step 1, which succeeded");

        ran.clear();
        new Migrator(root, List.of(step(1, false), step(2, false))).run(false);
        assertEquals(List.of(2), ran, "resumes where it stopped");
        assertEquals(2, field());
    }

    @Test
    void aFailedFirstMigrationRemovesFilesItCreated() throws IOException {
        config(0);
        assertThrows(MigrationException.class, () -> new Migrator(root, List.of(step(1, true))).run(false));
        assertEquals(0, field());
        assertFalse(Files.exists(root.resolve("created.json")));
    }

    @Test
    void originalsAreBackedUp() throws IOException {
        config(7);
        Migrator migrator = new Migrator(root, List.of(step(1, false)));
        migrator.run(false);
        try (var runs = Files.list(migrator.backups())) {
            Path run = runs.findFirst().orElseThrow();
            assertEquals("{\"field\":7}", Files.readString(run.resolve("v1").resolve("config.json")));
        }
    }

    @Test
    void newerDataRefusesToStart() throws IOException {
        config(0);
        new Migrator(root, List.of(step(1, false), step(2, false))).run(false);
        assertThrows(MigrationException.class, () -> new Migrator(root, List.of(step(1, false))).run(false));
    }

    @Test
    void duplicateVersionsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new Migrator(root, List.of(step(1, false), step(1, false))));
    }
}
