package dev.sirius.cloud.node.migration;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import dev.sirius.cloud.driver.migration.Migrator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdminPrefixMigrationTest {

    @TempDir
    Path root;

    private JsonArray migrate(String groups) throws IOException {
        Path file = root.resolve(AdminPrefixMigration.GROUPS);
        Files.createDirectories(file.getParent());
        Files.writeString(file, groups);
        Files.writeString(root.resolve("config.json"), "{}");
        new Migrator(root, List.of(new AdminPrefixMigration())).run(false);
        return JsonParser.parseString(Files.readString(file)).getAsJsonArray();
    }

    @Test
    void replacesTheOldDefault() throws IOException {
        JsonArray groups = migrate("[{\"name\":\"default\",\"prefix\":\"\"},"
                + "{\"name\":\"admin\",\"prefix\":\"&c[Admin] \"}]");
        assertEquals(AdminPrefixMigration.NEW_PREFIX, groups.get(1).getAsJsonObject().get("prefix").getAsString());
        assertEquals("", groups.get(0).getAsJsonObject().get("prefix").getAsString());
    }

    @Test
    void leavesAChosenPrefixAlone() throws IOException {
        JsonArray groups = migrate("[{\"name\":\"admin\",\"prefix\":\"&4Owner &c\"}]");
        assertEquals("&4Owner &c", groups.get(0).getAsJsonObject().get("prefix").getAsString());
    }

    @Test
    void doesNothingWithoutThePermissionsModule() throws IOException {
        Files.writeString(root.resolve("config.json"), "{}");
        Migrator migrator = new Migrator(root, List.of(new AdminPrefixMigration()));
        migrator.run(false);
        assertEquals(1, migrator.current());
    }
}
