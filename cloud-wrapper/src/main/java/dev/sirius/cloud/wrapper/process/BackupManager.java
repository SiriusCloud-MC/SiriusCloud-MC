package dev.sirius.cloud.wrapper.process;

import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.service.ServiceType;
import dev.sirius.cloud.protocol.packet.impl.BackupResultPacket;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Takes consistent backups of running services.
 *
 * <p>Consistency is the whole difficulty. A world zipped while the server is
 * writing to it can capture half a chunk. So for a Minecraft server:
 * {@code save-off} stops it writing, {@code save-all flush} forces everything
 * pending to disk, and the backup waits until the server <em>says</em> it saved
 * rather than guessing how long that takes. Only then are the files copied, and
 * {@code save-on} always runs afterwards - even if the copy failed - because a
 * server left with saving off loses everything from then on without complaint.
 *
 * <p>Archives go to {@code local/backups/<service>/} on this wrapper, the newest
 * {@code keep} are kept. Server jars, caches and logs are left out: they are
 * re-downloadable or worthless, and would make every archive tens of megabytes
 * bigger for nothing.
 */
public final class BackupManager {

    private static final CloudLogger LOGGER = CloudLogger.of("Backup");

    /** How long to wait for "Saved the game" before copying regardless. */
    private static final long SAVE_TIMEOUT_MILLIS = 60_000;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** Top-level names never archived. */
    private static final Set<String> SKIP = Set.of(
            "server.jar", "cache", "libraries", "versions", "logs", "crash-reports", "cloud-connection.json");

    private final Path backupRoot;
    private final ServiceProcessManager processes;
    private final Consumer<BackupResultPacket> resultSink;
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();

    public BackupManager(Path backupRoot, ServiceProcessManager processes, Consumer<BackupResultPacket> resultSink) {
        this.backupRoot = backupRoot;
        this.processes = processes;
        this.resultSink = resultSink;
    }

    /** Backs up a service on a virtual thread; a second request while one runs is ignored. */
    public void backup(UUID serviceId, int keep) {
        var process = processes.process(serviceId);
        if (process.isEmpty()) {
            resultSink.accept(new BackupResultPacket(serviceId, "?", false, "", 0,
                    "not running on this wrapper"));
            return;
        }
        if (!running.add(serviceId)) {
            LOGGER.debug("A backup of {} is already running", process.get().info().name());
            return;
        }
        Thread.ofVirtual().name("backup-" + process.get().info().name()).start(() -> {
            try {
                resultSink.accept(take(process.get(), keep));
            } finally {
                running.remove(serviceId);
            }
        });
    }

    private BackupResultPacket take(ServiceProcess process, int keep) {
        String name = process.info().name();
        boolean server = process.group().type() == ServiceType.SERVER && process.isAlive();

        try {
            if (server) {
                // Registered before the command is sent, so a quick server
                // cannot print the line before anyone is listening for it.
                var saved = process.awaitConsole(line -> line.contains("Saved the game"), SAVE_TIMEOUT_MILLIS);
                process.sendCommand("save-off");
                process.sendCommand("save-all flush");
                try {
                    saved.join();
                } catch (RuntimeException timeout) {
                    LOGGER.warn("{} did not confirm its save within {}s; backing up what is on disk",
                            name, SAVE_TIMEOUT_MILLIS / 1000);
                }
            }

            Path directory = backupRoot.resolve(name);
            Files.createDirectories(directory);
            Path archive = directory.resolve(name + "-" + LocalDateTime.now().format(STAMP) + ".zip");
            Path partial = archive.resolveSibling(archive.getFileName() + ".part");

            int skipped = zip(process.directory(), partial);
            Files.move(partial, archive, StandardCopyOption.REPLACE_EXISTING);
            prune(directory, keep);

            long size = Files.size(archive);
            String note = skipped > 0 ? skipped + " file(s) could not be read and were skipped" : "";
            LOGGER.info("Backed up {} to {} ({} MB){}", name, archive.getFileName(), size / (1024 * 1024),
                    note.isEmpty() ? "" : " - " + note);
            return new BackupResultPacket(process.info().uniqueId(), name, true, archive.toString(), size, note);

        } catch (IOException exception) {
            LOGGER.warn("Backup of {} failed: {}", name, exception.getMessage());
            return new BackupResultPacket(process.info().uniqueId(), name, false, "", 0, exception.getMessage());
        } finally {
            if (server) {
                process.sendCommand("save-on");
            }
        }
    }

    /**
     * Zips a directory, skipping what is not worth keeping.
     *
     * <p>A file that cannot be read is skipped and counted rather than failing
     * the whole backup: on Windows a lock file held by the running server is
     * unreadable, and losing every backup over {@code session.lock} would be
     * absurd.
     *
     * @return how many files were skipped because they could not be read
     */
    static int zip(Path source, Path target) throws IOException {
        int[] skipped = {0};
        try (OutputStream out = Files.newOutputStream(target);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (!directory.equals(source) && directory.getParent().equals(source)
                            && SKIP.contains(directory.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (file.getParent().equals(source) && SKIP.contains(file.getFileName().toString())) {
                        return FileVisitResult.CONTINUE;
                    }
                    // Forward slashes whatever the platform: that is what the zip
                    // format specifies, and a Windows-made archive with
                    // backslashes unpacks into a single oddly named file on Linux.
                    String entry = source.relativize(file).toString().replace('\\', '/');
                    try {
                        byte[] content = Files.readAllBytes(file);
                        zip.putNextEntry(new ZipEntry(entry));
                        zip.write(content);
                        zip.closeEntry();
                    } catch (IOException unreadable) {
                        skipped[0]++;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exception) {
                    skipped[0]++;
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            Files.deleteIfExists(target);
            throw exception;
        }
        return skipped[0];
    }

    /** Keeps the newest {@code keep} archives. Names sort by time, so name order is age order. */
    static void prune(Path directory, int keep) throws IOException {
        List<Path> archives;
        try (Stream<Path> files = Files.list(directory)) {
            archives = files.filter(file -> file.getFileName().toString().endsWith(".zip"))
                    .sorted(Comparator.comparing(file -> file.getFileName().toString()))
                    .toList();
        }
        for (int i = 0; i < archives.size() - keep; i++) {
            Files.deleteIfExists(archives.get(i));
        }
    }
}
