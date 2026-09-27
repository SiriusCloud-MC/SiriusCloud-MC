package dev.sirius.cloud.node.cluster;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The files a cluster keeps identical on every node, and their content hashes.
 *
 * <p>What is replicated is the network's data: groups, each module's folder,
 * the JSON database, the key-value store and the data version. Jars are not:
 * they are this node's program, and a leader on an older build pushing its
 * modules onto an updated node would quietly downgrade it. Neither is
 * anything under {@code local/} that describes this machine alone.
 *
 * <p>Hashes are cached by size and modification time, so a scan of thousands
 * of unchanged files costs a {@code stat} each rather than a read.
 */
public final class FileIndex {

    /** Directories and files, relative to the node directory, whose contents are replicated. */
    static final List<String> ROOTS = List.of(
            "groups", "modules", "local/database", "local/store.json", "local/data-version.json");

    private record Cached(long size, long modified, String hash) {
    }

    private final Path root;
    private final Map<String, Cached> cache = new HashMap<>();

    public FileIndex(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** Whether a path is one this index manages - the only paths a follower will write or delete. */
    public static boolean replicated(String relative) {
        String name = relative.substring(relative.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (name.endsWith(".jar") || name.endsWith(".tmp") || name.endsWith(".part") || name.equals(".lock")
                || relative.contains("..") || relative.startsWith("/")) {
            return false;
        }
        return ROOTS.stream().anyMatch(prefix -> relative.equals(prefix) || relative.startsWith(prefix + "/"));
    }

    /** Every replicated file and its hash, sorted by path. */
    public synchronized Map<String, String> manifest() throws IOException {
        Map<String, String> manifest = new TreeMap<>();
        for (String prefix : ROOTS) {
            Path start = root.resolve(prefix);
            if (Files.notExists(start)) {
                continue;
            }
            if (Files.isRegularFile(start)) {
                add(manifest, start);
                continue;
            }
            try (Stream<Path> walk = Files.walk(start)) {
                for (Path file : (Iterable<Path>) walk::iterator) {
                    if (Files.isRegularFile(file)) {
                        add(manifest, file);
                    }
                }
            } catch (UncheckedIOException exception) {
                // A file removed while walking; the next scan sees the new state.
                if (!(exception.getCause() instanceof NoSuchFileException)) {
                    throw exception.getCause();
                }
            }
        }
        cache.keySet().retainAll(manifest.keySet());
        return manifest;
    }

    private void add(Map<String, String> manifest, Path file) throws IOException {
        String relative = relative(file);
        if (!replicated(relative)) {
            return;
        }
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(file, BasicFileAttributes.class);
        } catch (NoSuchFileException gone) {
            return;
        }
        Cached cached = cache.get(relative);
        long modified = attributes.lastModifiedTime().toMillis();
        if (cached == null || cached.size() != attributes.size() || cached.modified() != modified) {
            byte[] content;
            try {
                content = Files.readAllBytes(file);
            } catch (NoSuchFileException gone) {
                return;
            }
            cached = new Cached(attributes.size(), modified, hash(content));
            cache.put(relative, cached);
        }
        manifest.put(relative, cached.hash());
    }

    public byte[] read(String relative) throws IOException {
        return Files.readAllBytes(resolve(relative));
    }

    /** Writes through a temporary file, so a reader never sees half a file. */
    public void write(String relative, byte[] content) throws IOException {
        Path target = resolve(relative);
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".part");
        Files.write(temporary, content);
        move(temporary, target);
    }

    /** Streams a large file in, piece by piece; {@link #finish} puts it in place. */
    public void append(String relative, byte[] chunk, boolean first) throws IOException {
        Path target = resolve(relative);
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".part");
        if (first) {
            Files.write(temporary, chunk);
        } else {
            Files.write(temporary, chunk, java.nio.file.StandardOpenOption.APPEND);
        }
    }

    public void finish(String relative) throws IOException {
        Path target = resolve(relative);
        move(target.resolveSibling(target.getFileName() + ".part"), target);
    }

    public void delete(String relative) throws IOException {
        Files.deleteIfExists(resolve(relative));
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path resolve(String relative) {
        if (!replicated(relative)) {
            throw new IllegalArgumentException("Not a replicated path: " + relative);
        }
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("Outside the node directory: " + relative);
        }
        return file;
    }

    private String relative(Path file) {
        return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    static String hash(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content), 0, 16);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
