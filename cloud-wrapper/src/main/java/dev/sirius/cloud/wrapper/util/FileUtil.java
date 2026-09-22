package dev.sirius.cloud.wrapper.util;

import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.platform.Platform;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Filesystem helpers that behave the same on Linux and Windows.
 *
 * <p>The interesting one is {@link #deleteRecursively(Path)} — see its comment.
 */
public final class FileUtil {

    private static final CloudLogger LOGGER = CloudLogger.of(FileUtil.class);

    private static final int DELETE_ATTEMPTS = 20;
    private static final long DELETE_RETRY_MILLIS = 250;

    private FileUtil() {
    }

    /** Recursively copies {@code source} into {@code target}, overwriting files. */
    public static void copyDirectory(Path source, Path target) throws IOException {
        if (Files.notExists(source)) {
            return;
        }
        Files.createDirectories(target);

        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                Files.createDirectories(target.resolve(source.relativize(directory).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                // relativize() then toString() rather than passing the Path
                // directly: resolving a path from one FileSystem against
                // another throws, and this keeps separators correct on both.
                Path destination = target.resolve(source.relativize(file).toString());
                Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Deletes a directory tree, retrying while Windows releases its handles.
     *
     * <p>On Linux this succeeds on the first attempt every time. On Windows it
     * frequently does not: file deletion fails while any handle remains open,
     * and a Minecraft server that has just exited may still have its
     * memory-mapped region files held by the OS for a short window after the
     * process is gone. That makes wiping a service directory an intermittent
     * failure that never reproduces on a Linux dev machine.
     *
     * <p>So: bounded retries with a short delay, plus a GC hint on Windows,
     * because mapped buffers are only unmapped when their referents are
     * collected. Failure is logged rather than thrown — a leftover directory is
     * a much smaller problem than a wrapper that refuses to start services.
     */
    public static void deleteRecursively(Path path) {
        if (Files.notExists(path)) {
            return;
        }

        IOException last = null;
        for (int attempt = 1; attempt <= DELETE_ATTEMPTS; attempt++) {
            try {
                deleteTree(path);
                return;
            } catch (IOException exception) {
                last = exception;

                if (Platform.isWindows()) {
                    System.gc();
                }
                try {
                    Thread.sleep(DELETE_RETRY_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        LOGGER.warn("Could not fully delete {} after {} attempts: {}",
                path, DELETE_ATTEMPTS, last == null ? "unknown" : last.getMessage());
    }

    private static void deleteTree(Path path) throws IOException {
        try (Stream<Path> paths = Files.walk(path)) {
            // Deepest first, so directories are empty by the time we reach them.
            for (Path entry : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }

    /** Writes text as UTF-8, creating parent directories as needed. */
    public static void writeString(Path path, String content) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, content, java.nio.charset.StandardCharsets.UTF_8);
    }
}
