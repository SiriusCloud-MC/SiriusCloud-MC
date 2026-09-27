package dev.sirius.cloud.wrapper.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupManagerTest {

    @TempDir
    Path temp;

    @Test
    void archivesTheWorldAndLeavesOutWhatIsNotWorthKeeping() throws Exception {
        Path service = temp.resolve("Survival-1");
        Files.createDirectories(service.resolve("world/region"));
        Files.createDirectories(service.resolve("plugins/Essentials"));
        Files.createDirectories(service.resolve("logs"));
        Files.createDirectories(service.resolve("cache"));
        Files.writeString(service.resolve("world/region/r.0.0.mca"), "chunks");
        Files.writeString(service.resolve("plugins/Essentials/config.yml"), "x: 1");
        Files.writeString(service.resolve("server.properties"), "server-port=41000");
        Files.writeString(service.resolve("server.jar"), "sixty megabytes");
        Files.writeString(service.resolve("logs/latest.log"), "noise");
        Files.writeString(service.resolve("cache/mojang.jar"), "noise");
        Files.writeString(service.resolve("cloud-connection.json"), "{\"token\":\"secret\"}");

        Path archive = temp.resolve("backup.zip");
        assertEquals(0, BackupManager.zip(service, archive));

        List<String> entries = new ArrayList<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                entries.add(all.nextElement().getName());
            }
        }

        assertTrue(entries.contains("world/region/r.0.0.mca"));
        assertTrue(entries.contains("plugins/Essentials/config.yml"));
        assertTrue(entries.contains("server.properties"));
        assertFalse(entries.contains("server.jar"));
        assertFalse(entries.stream().anyMatch(name -> name.startsWith("logs/") || name.startsWith("cache/")));
        // The connection file holds the service's credential; a backup handed
        // to somebody must not carry it.
        assertFalse(entries.contains("cloud-connection.json"));
        // The zip format says forward slashes, whatever made the archive.
        assertFalse(entries.stream().anyMatch(name -> name.contains("\\")));
    }

    @Test
    void keepsOnlyTheNewestArchives() throws Exception {
        Path directory = temp.resolve("backups");
        Files.createDirectories(directory);
        for (String stamp : List.of("20260101-000000", "20260102-000000", "20260103-000000", "20260104-000000")) {
            Files.writeString(directory.resolve("Survival-1-" + stamp + ".zip"), stamp);
        }
        Files.writeString(directory.resolve("notes.txt"), "not an archive");

        BackupManager.prune(directory, 2);

        List<String> left;
        try (Stream<Path> files = Files.list(directory)) {
            left = files.map(file -> file.getFileName().toString()).sorted().toList();
        }
        assertEquals(List.of("Survival-1-20260103-000000.zip", "Survival-1-20260104-000000.zip", "notes.txt"), left);
    }
}
